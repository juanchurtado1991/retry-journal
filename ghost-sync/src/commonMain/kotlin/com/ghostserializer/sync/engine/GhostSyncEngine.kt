package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.DiskQueueConstants
import com.ghostserializer.sync.queue.HeadReplayPrepareResult
import com.ghostserializer.sync.queue.LifecycleGate
import com.ghostserializer.sync.queue.QueueEntry
import com.ghostserializer.sync.queue.QueueEntryId
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains [queue], replaying each entry through [HttpReplayer] and applying the outcome: 2xx
 * delivers, a non-retry-worthy 4xx dead-letters, anything else stops the flush early and leaves
 * the entry queued for next time. All Ktor request-building mechanics live in [HttpReplayer] —
 * this class only orchestrates the loop and interprets outcomes.
 *
 * **Threading:** [flush], [getEntry], [getEntryAndStatus], and [getStatus] share [replayMutex],
 * so concurrent callers in the same process cannot replay the same head entry twice. A
 * cross-process [ReplayClaim][com.ghostserializer.sync.queue.ReplayClaim] on [queue] covers two
 * processes (app + worker) sharing the same queue file.
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

    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult = replayMutex.withLock {
        withReplayLifecycle {
            flushLocked(client, onProgress)
        }
    }

    /** Read-only peek at the oldest readable entry — safe for UI inspection under [replayMutex].
     * Returns `null` when the queue is empty **or** when the head is blocked by a cross-process
     * replay claim; use [getHeadState] to distinguish blocked from empty ( [getEntry] may still
     * return the head entry while another process holds the claim). For manual replay prefer
     * [getEntryAndStatus], which claims the head and sends atomically. */
    suspend fun getEntry(): QueueEntry? = replayMutex.withLock {
        withReplayLifecycle { queue.peek() }
    }

    /** Inspects the queue head without claiming it — distinguishes empty, blocked, and ready. */
    suspend fun getHeadState(): QueueHeadState = replayMutex.withLock {
        withReplayLifecycle {
            if (queue.isHeadBlockedByActiveClaimLocked()) {
                return@withReplayLifecycle QueueHeadState.Blocked
            }
            val entry = queue.peek()
            if (entry == null) {
                QueueHeadState.Empty
            } else {
                QueueHeadState.Ready(entry)
            }
        }
    }

    /** Atomically claims the head entry and replays it under [replayMutex] — the thread-safe manual
     * alternative to separate [getEntry] + [getStatus] calls. The entry stays on the queue; the
     * caller applies the outcome ([DiskQueue.completeHeadReplay], dead-letter, etc.) just like
     * [flush] would. */
    suspend fun getEntryAndStatus(client: HttpClient): EntryReplayResult =
        replayMutex.withLock {
            withReplayLifecycle {
                replayHeadEntry(client)
            }
        }

    /** Replays [entry] when it is still the queue head, under an active [ReplayClaim]. Prefer
     * [getEntryAndStatus] for concurrent callers; this path exists for serialized manual loops
     * that already hold a [QueueEntry] from [getEntry]. */
    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode =
        replayMutex.withLock {
            withReplayLifecycle {
                replayClaimedHeadForEntry(client, entry)
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
            val prepared = prepareNextHeadEntry() ?: return FlushResult(
                delivered,
                deadLettered,
                stoppedEarly = false,
            )
            if (prepared is HeadReplayPrepareResult.HeadBlocked) {
                return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }
            prepared as HeadReplayPrepareResult.Ready

            val status = replayEntryOrStop(client, prepared.entry) ?: return FlushResult(
                delivered,
                deadLettered,
                stoppedEarly = true,
            )

            when (val outcome = applyReplayOutcome(
                status,
                prepared.entry,
                onProgress,
                delivered,
                deadLettered,
            )) {
                is ReplayOutcome.Continue -> {
                    delivered = outcome.delivered
                    deadLettered = outcome.deadLettered
                }

                is ReplayOutcome.Stop -> {
                    return FlushResult(
                        delivered = outcome.delivered,
                        deadLettered = outcome.deadLettered,
                        stoppedEarly = true,
                    )
                }
            }
        }
    }

    private suspend fun prepareNextHeadEntry(): HeadReplayPrepareResult? =
        when (val result = queue.prepareHeadForReplay()) {
            is HeadReplayPrepareResult.Empty -> null
            is HeadReplayPrepareResult.HeadBlocked -> result
            is HeadReplayPrepareResult.Ready -> result
        }

    private suspend fun replayEntryOrStop(
        client: HttpClient,
        entry: QueueEntry,
    ): HttpStatusCode? = try {
        withReplayClaimRenewal(entry.id) {
            trySend(client, entry)
        }
    } catch (e: CancellationException) {
        queue.abortHeadReplayClaim()
        throw e
    } ?: run {
        queue.abortHeadReplayClaim()
        null
    }

    private suspend fun applyReplayOutcome(
        status: HttpStatusCode,
        entry: QueueEntry,
        onProgress: suspend (FlushProgress) -> Unit,
        delivered: Int = 0,
        deadLettered: Int = 0,
    ): ReplayOutcome = when {
        status.isSuccess() -> handleSuccessfulDelivery(entry, delivered, deadLettered, onProgress)
        shouldDeadLetter(status) -> handleDeadLetterDelivery(entry, delivered, deadLettered, onProgress)
        else -> {
            queue.abortHeadReplayClaim()
            ReplayOutcome.Stop(delivered, deadLettered)
        }
    }

    private suspend fun handleSuccessfulDelivery(
        entry: QueueEntry,
        delivered: Int,
        deadLettered: Int,
        onProgress: suspend (FlushProgress) -> Unit,
    ): ReplayOutcome {
        if (!completeHeadReplayOrStop(entry.id)) {
            return ReplayOutcome.Stop(delivered, deadLettered)
        }
        onProgress(FlushProgress.Delivered(entry.id))
        return ReplayOutcome.Continue(delivered + 1, deadLettered)
    }

    private suspend fun handleDeadLetterDelivery(
        entry: QueueEntry,
        delivered: Int,
        deadLettered: Int,
        onProgress: suspend (FlushProgress) -> Unit,
    ): ReplayOutcome = try {
        deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
        if (!completeHeadReplayOrStop(entry.id)) {
            ReplayOutcome.Stop(delivered, deadLettered)
        } else {
            onProgress(FlushProgress.DeadLettered(entry.id))
            ReplayOutcome.Continue(delivered, deadLettered + 1)
        }
    } catch (e: CancellationException) {
        queue.abortHeadReplayClaim()
        throw e
    } catch (_: Throwable) {
        queue.abortHeadReplayClaim()
        ReplayOutcome.Stop(delivered, deadLettered)
    }

    private suspend fun replayHeadEntry(client: HttpClient): EntryReplayResult =
        when (val prepared = queue.prepareHeadForReplay()) {
            is HeadReplayPrepareResult.Empty -> EntryReplayResult.Empty
            is HeadReplayPrepareResult.HeadBlocked -> EntryReplayResult.HeadBlocked
            is HeadReplayPrepareResult.Ready -> sendPreparedHeadEntry(client, prepared.entry)
        }

    private suspend fun sendPreparedHeadEntry(
        client: HttpClient,
        entry: QueueEntry,
    ): EntryReplayResult = try {
        withReplayClaimRenewal(entry.id) {
            EntryReplayResult.Ready(entry, httpReplayer.send(client, entry))
        }
    } catch (e: CancellationException) {
        queue.abortHeadReplayClaim()
        throw e
    } catch (_: Throwable) {
        queue.abortHeadReplayClaim()
        EntryReplayResult.ReplayFailed
    }

    private suspend fun replayClaimedHeadForEntry(
        client: HttpClient,
        entry: QueueEntry,
    ): HttpStatusCode {
        httpReplayer.assertSafeToReplayWith(client)
        val prepared = requirePreparedHeadForEntry(entry)
        return try {
            withReplayClaimRenewal(prepared.id) {
                httpReplayer.send(client, prepared)
            }
        } catch (e: CancellationException) {
            queue.abortHeadReplayClaim()
            throw e
        } catch (e: Throwable) {
            queue.abortHeadReplayClaim()
            throw e
        }
    }

    private suspend fun requirePreparedHeadForEntry(entry: QueueEntry): QueueEntry =
        when (val prepared = queue.prepareHeadForReplay()) {
            is HeadReplayPrepareResult.Empty -> error(SyncEngineConstants.GET_STATUS_QUEUE_EMPTY_MESSAGE)
            is HeadReplayPrepareResult.HeadBlocked -> error(SyncEngineConstants.GET_STATUS_HEAD_BLOCKED_MESSAGE)
            is HeadReplayPrepareResult.Ready -> {
                if (prepared.entry.id != entry.id) {
                    queue.abortHeadReplayClaim()
                    error(SyncEngineConstants.GET_STATUS_ENTRY_NOT_HEAD_MESSAGE)
                }
                prepared.entry
            }
        }

    private suspend fun <T> withReplayClaimRenewal(
        entryId: QueueEntryId,
        block: suspend () -> T,
    ): T = coroutineScope {
        val renewalJob = launch {
            while (isActive) {
                delay(DiskQueueConstants.REPLAY_CLAIM_RENEWAL_INTERVAL_MILLIS)
                queue.renewHeadReplayClaim(entryId)
            }
        }
        try {
            block()
        } finally {
            renewalJob.cancelAndJoin()
        }
    }

    private suspend fun completeHeadReplayOrStop(entryId: QueueEntryId): Boolean = try {
        queue.completeHeadReplay(entryId)
        true
    } catch (_: Throwable) {
        false
    }

    private suspend fun trySend(client: HttpClient, entry: QueueEntry): HttpStatusCode? = try {
        httpReplayer.send(client, entry)
    } catch (e: CancellationException) {
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
