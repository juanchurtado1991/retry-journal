package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.HeadReplayPrepareResult
import com.ghostserializer.sync.queue.LifecycleGate
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains [queue], replaying each entry through [HttpReplayer] and applying the outcome: 2xx
 * delivers, a non-retry-worthy 4xx dead-letters, anything else stops the flush early and leaves
 * the entry queued for next time. All Ktor request-building mechanics live in [HttpReplayer] —
 * this class only orchestrates the loop and interprets outcomes.
 *
 * **Threading:** [flush], [getEntryAndStatus], and [getStatus] share [replayMutex], so concurrent
 * callers in the same process cannot replay the same head entry twice. A cross-process
 * [ReplayClaim][com.ghostserializer.sync.queue.ReplayClaim] on [queue] covers two processes
 * (app + worker) sharing the same queue file.
 *
 * **Delivery semantics:** replay is **at-least-once** — a [kotlinx.coroutines.CancellationException]
 * after the server already accepted a 2xx but before [DiskQueue.completeHeadReplay] runs can leave
 * the entry on the queue for a future `flush()`. Idempotent endpoints or server-side dedup keys
 * are the caller's responsibility.
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    private val httpReplayer = HttpReplayer()
    private val replayMutex = Mutex()
    private val lifecycleGate = LifecycleGate(
        closedMessage = SyncEngineConstants.ENGINE_CLOSED_MESSAGE,
        closeWhileBusyMessage = SyncEngineConstants.CLOSE_WHILE_REPLAY_IN_FLIGHT_MESSAGE,
    )

    /** Used by [com.ghostserializer.sync.GhostSync.close] before tearing down clients. */
    internal fun closeForShutdown() {
        lifecycleGate.close()
    }

    fun hasActiveReplaySession(): Boolean = lifecycleGate.hasActiveSessions()

    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult = replayMutex.withLock {
        withReplayLifecycle {
            flushLocked(client, onProgress)
        }
    }

    /** Read-only peek at the oldest readable entry — safe for UI inspection. For manual replay
     * loops prefer [getEntryAndStatus], which holds [replayMutex] across peek and send so two
     * threads cannot duplicate delivery. */
    suspend fun getEntry(): QueueEntry? = queue.peek()

    /** Atomically peeks the head entry and replays it under [replayMutex] — the thread-safe manual
     * alternative to separate [getEntry] + [getStatus] calls. The entry stays on the queue; the
     * caller applies the outcome ([DiskQueue.completeHeadReplay], dead-letter, etc.) just like
     * [flush] would. */
    suspend fun getEntryAndStatus(client: HttpClient): EntryReplaySnapshot? =
        replayMutex.withLock {
            withReplayLifecycle {
                when (val prepared = queue.prepareHeadForReplay()) {
                    is HeadReplayPrepareResult.Empty -> {
                        return@withReplayLifecycle null
                    }

                    is HeadReplayPrepareResult.HeadBlocked -> {
                        return@withReplayLifecycle null
                    }

                    is HeadReplayPrepareResult.Ready -> {
                        try {
                            val status = httpReplayer.send(client, prepared.entry)
                            EntryReplaySnapshot(prepared.entry, status)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            queue.abortHeadReplayClaim()
                            throw e
                        } catch (_: Throwable) {
                            queue.abortHeadReplayClaim()
                            null
                        }
                    }
                }
            }
        }

    data class EntryReplaySnapshot(
        val entry: QueueEntry,
        val status: HttpStatusCode,
    )

    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode =
        replayMutex.withLock {
            withReplayLifecycle {
                httpReplayer.send(client, entry)
            }
        }

    private suspend inline fun <T> withReplayLifecycle(crossinline block: suspend () -> T): T {
        lifecycleGate.enter()
        try {
            return block()
        } finally {
            lifecycleGate.leave()
        }
    }

    private suspend fun flushLocked(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit,
    ): FlushResult {
        httpReplayer.assertSafeToReplayWith(client)

        var delivered = 0
        var deadLettered = 0

        while (true) {
            val prepared = when (val result = queue.prepareHeadForReplay()) {
                is HeadReplayPrepareResult.Empty -> return FlushResult(delivered, deadLettered, stoppedEarly = false)
                is HeadReplayPrepareResult.HeadBlocked -> {
                    return FlushResult(delivered, deadLettered, stoppedEarly = true)
                }
                is HeadReplayPrepareResult.Ready -> result
            }
            val entry = prepared.entry

            val status = try {
                trySend(client, entry)
            } catch (e: kotlinx.coroutines.CancellationException) {
                queue.abortHeadReplayClaim()
                throw e
            } ?: run {
                queue.abortHeadReplayClaim()
                return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }

            when {
                status.isSuccess() -> {
                    queue.completeHeadReplay(entry.id)
                    delivered++
                    onProgress(FlushProgress.Delivered(entry.id))
                }

                shouldDeadLetter(status) -> {
                    try {
                        deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                        queue.completeHeadReplay(entry.id)
                        deadLettered++
                        onProgress(FlushProgress.DeadLettered(entry.id))
                    } catch (_: Throwable) {
                        queue.abortHeadReplayClaim()
                        return FlushResult(delivered, deadLettered, stoppedEarly = true)
                    }
                }

                else -> {
                    queue.abortHeadReplayClaim()
                    return FlushResult(delivered, deadLettered, stoppedEarly = true)
                }
            }
        }
    }

    private suspend fun trySend(client: HttpClient, entry: QueueEntry): HttpStatusCode? = try {
        httpReplayer.send(client, entry)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: OfflineQueuedException) {
        null
    } catch (_: Throwable) {
        null
    }

    private fun shouldDeadLetter(status: HttpStatusCode): Boolean {
        val value = status.value
        return value in CLIENT_ERROR_STATUS_LOWER_BOUND..CLIENT_ERROR_STATUS_UPPER_BOUND &&
            value !in RETRY_WORTHY_CLIENT_ERROR_STATUSES
    }
}
