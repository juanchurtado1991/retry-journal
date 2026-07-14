package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Drains [queue], replaying each entry through [HttpReplayer] and applying the outcome: 2xx
 * delivers, a non-retry-worthy 4xx dead-letters, anything else stops the flush early and leaves
 * the entry queued for next time. All Ktor request-building mechanics live in [HttpReplayer] —
 * this class only orchestrates the loop and interprets outcomes.
 *
 * **Threading:** [flush] and [getStatus] share [replayMutex], so concurrent callers cannot replay
 * the same head entry twice. [hasActiveReplaySession] covers the full span of either call,
 * including an in-flight network round-trip — see [com.ghostserializer.sync.GhostSync.close].
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    private val httpReplayer = HttpReplayer()
    private val replayMutex = Mutex()

    /** Nonzero while any [flush] or [getStatus] call on this instance is in progress, including
     * time spent inside a real network round-trip — not just the brief [DiskQueue] peek/remove
     * calls around it. [Volatile] lets [hasActiveReplaySession]'s unsynchronized read see the
     * latest value, same tradeoff [DiskQueue] documents on [activeOperationCount][com.ghostserializer.sync.queue.DiskQueue]. */
    @Volatile
    private var activeReplaySessionCount = 0

    /** Best-effort guard for [com.ghostserializer.sync.GhostSync.close] and for callers wiring
     * [flush] manually: do not close the replay [HttpClient] while this returns true. */
    fun hasActiveReplaySession(): Boolean = activeReplaySessionCount > 0

    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult = replayMutex.withLock {
        activeReplaySessionCount++
        try {
            return flushLocked(client, onProgress)
        } finally {
            activeReplaySessionCount--
        }
    }

    suspend fun getEntry(): QueueEntry? = queue.peek()

    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode =
        replayMutex.withLock {
            activeReplaySessionCount++
            try {
                httpReplayer.send(client, entry)
            } finally {
                activeReplaySessionCount--
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
            val entry = getEntry() ?: return FlushResult(delivered, deadLettered, stoppedEarly = false)
            val status = trySend(client, entry) ?: return FlushResult(delivered, deadLettered, stoppedEarly = true)

            when {
                status.isSuccess() -> {
                    queue.remove(entry.id)
                    delivered++
                    onProgress(FlushProgress.Delivered(entry.id))
                }

                shouldDeadLetter(status) -> {
                    try {
                        deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                        queue.remove(entry.id)
                        deadLettered++
                        onProgress(FlushProgress.DeadLettered(entry.id))
                    } catch (_: Throwable) {
                        return FlushResult(delivered, deadLettered, stoppedEarly = true)
                    }
                }

                else -> return FlushResult(delivered, deadLettered, stoppedEarly = true)
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
