package com.retryjournal.engine

import com.retryjournal.client.OfflineQueuedException
import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.retryjournal.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.retryjournal.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.disk.DiskQueueConstants.REPLAY_CLAIM_RENEWAL_INTERVAL_MILLIS
import com.retryjournal.queue.inspectHeadLocked
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single owner of head replay transitions — [RetryJournalEngine.flush] delegates here exclusively.
 * Manual replay APIs were removed in 1.0.0; all delivery state changes flow through this class.
 */
internal class HeadReplayExecutor(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
    private val httpReplayer: HttpReplayer,
) {

    suspend fun resolveHeadState(): HeadEntryState = queue.withQueueLock {
        val snapshot = queue.inspectHeadLocked()
        val entry = snapshot.entry
            ?: return@withQueueLock if (snapshot.blockedByClaim) {
                HeadEntryState.Blocked
            } else {
                HeadEntryState.Absent
            }

        mapJournalOutcome(snapshot.journalOutcome)?.let { outcome ->
            return@withQueueLock HeadEntryState
                .AwaitingLocalRemoval(entry, outcome)
        }
        if (snapshot.blockedByClaim) {
            return@withQueueLock HeadEntryState.Blocked
        }
        HeadEntryState.AwaitingReplay(entry)
    }

    suspend fun drain(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit,
    ): FlushResult {
        httpReplayer.assertSafeToReplayWith(client)

        var delivered = 0
        var deadLettered = 0

        while (true) {
            when (val prepared = queue.prepareHeadForReplay()) {
                is HeadReplayPrepareResult.Empty -> {
                    return FlushResult(delivered, deadLettered, stoppedEarly = false)
                }

                is HeadReplayPrepareResult.HeadBlocked -> {
                    return FlushResult(delivered, deadLettered, stoppedEarly = true)
                }

                is HeadReplayPrepareResult.Ready -> {
                    val entry = prepared.entry
                    val status = replayEntryOrStop(client, entry)
                        ?: return FlushResult(
                            delivered,
                            deadLettered,
                            stoppedEarly = true
                        )

                    when (val outcome = applyReplayOutcome(
                        status,
                        entry,
                        onProgress,
                        delivered,
                        deadLettered
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
                                persistenceFailed = outcome.persistenceFailed,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun replayEntryOrStop(
        client: HttpClient,
        entry: QueueEntry,
    ): HttpStatusCode? {
        resolvePendingDeliveryStatus(entry)?.let { return it }
        return try {
            withReplayClaimRenewal(entry.id) {
                trySend(client, entry)
            }
        } catch (e: CancellationException) {
            abortHeadReplayClaimNonCancellable()
            throw e
        } ?: run {
            queue.abortHeadReplayClaim()
            null
        }
    }

    private fun resolvePendingOutcome(entry: QueueEntry): DeliveryOutcome? {
        val result = DeliveryJournal.read(queue.fileSystem, queue.path, entry.id.sequenceId)
        val pending = DeliveryJournal.pendingForSequence(result, entry.id.sequenceId) ?: return null
        return mapJournalOutcome(pending.outcome)
    }

    private fun mapJournalOutcome(outcome: String?): DeliveryOutcome? = when (outcome) {
        DeliveryJournal.OUTCOME_DELIVERED -> DeliveryOutcome.Delivered
        DeliveryJournal.OUTCOME_DEAD_LETTERED -> DeliveryOutcome.DeadLettered
        else -> null
    }

    private fun resolvePendingDeliveryStatus(entry: QueueEntry): HttpStatusCode? =
        when (resolvePendingOutcome(entry)) {
            DeliveryOutcome.Delivered -> HttpStatusCode.OK
            DeliveryOutcome.DeadLettered -> HttpStatusCode.BadRequest
            null -> null
        }

    private suspend fun applyReplayOutcome(
        status: HttpStatusCode,
        entry: QueueEntry,
        onProgress: suspend (FlushProgress) -> Unit,
        delivered: Int,
        deadLettered: Int,
    ): ReplayOutcome {
        val pending = resolvePendingOutcome(entry)
        return when {
            status.isSuccess() && pending == DeliveryOutcome.Delivered -> {
                finishDeliveredFromJournal(entry, onProgress, delivered, deadLettered)
            }

            status.isSuccess() -> {
                handleSuccessfulDelivery(entry, delivered, deadLettered, onProgress)
            }

            shouldDeadLetter(status) && pending == DeliveryOutcome.DeadLettered -> {
                finishDeadLetteredFromJournal(entry, onProgress, delivered, deadLettered)
            }

            shouldDeadLetter(status) -> {
                handleDeadLetterDelivery(entry, delivered, deadLettered, onProgress)
            }

            else -> {
                queue.abortHeadReplayClaim()
                ReplayOutcome.Stop(delivered, deadLettered)
            }
        }
    }

    private suspend fun finishDeliveredFromJournal(
        entry: QueueEntry,
        onProgress: suspend (FlushProgress) -> Unit,
        delivered: Int,
        deadLettered: Int,
    ): ReplayOutcome {
        if (!completeHeadReplayOrStop(entry.id)) {
            return ReplayOutcome.Stop(delivered, deadLettered, persistenceFailed = true)
        }
        DeliveryJournal.delete(queue.fileSystem, queue.path, entry.id.sequenceId)
        onProgress(FlushProgress.Delivered(entry.id))
        return ReplayOutcome.Continue(delivered + 1, deadLettered)
    }

    private suspend fun finishDeadLetteredFromJournal(
        entry: QueueEntry,
        onProgress: suspend (FlushProgress) -> Unit,
        delivered: Int,
        deadLettered: Int,
    ): ReplayOutcome = try {
        if (!deadLetterQueue.hasMatchingEntry(
                entry.meta.method,
                entry.meta.url,
                entry.meta.headers,
                entry.body,
            )
        ) {
            deadLetterQueue.record(
                entry.meta.method,
                entry.meta.url,
                entry.meta.headers,
                entry.body
            )
        }

        if (!completeHeadReplayOrStop(entry.id)) {
            ReplayOutcome.Stop(
                delivered,
                deadLettered,
                persistenceFailed = true
            )
        } else {
            DeliveryJournal.delete(
                queue.fileSystem,
                queue.path,
                entry.id.sequenceId
            )
            onProgress(FlushProgress.DeadLettered(entry.id))
            ReplayOutcome.Continue(delivered, deadLettered + 1)
        }
    } catch (e: CancellationException) {
        abortHeadReplayClaimNonCancellable()
        throw e
    } catch (_: Throwable) {
        queue.abortHeadReplayClaim()
        ReplayOutcome.Stop(delivered, deadLettered)
    }

    private suspend fun handleSuccessfulDelivery(
        entry: QueueEntry,
        delivered: Int,
        deadLettered: Int,
        onProgress: suspend (FlushProgress) -> Unit,
    ): ReplayOutcome = try {
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            entry.id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        if (!completeHeadReplayOrStop(entry.id)) {
            ReplayOutcome.Stop(
                delivered,
                deadLettered,
                persistenceFailed = true
            )
        } else {
            DeliveryJournal.delete(
                queue.fileSystem,
                queuePath = queue.path,
                entry.id.sequenceId
            )
            onProgress(FlushProgress.Delivered(entry.id))
            ReplayOutcome.Continue(delivered + 1, deadLettered)
        }
    } catch (e: CancellationException) {
        abortHeadReplayClaimNonCancellable()
        throw e
    } catch (_: Throwable) {
        queue.abortHeadReplayClaim()
        ReplayOutcome.Stop(delivered, deadLettered)
    }

    private suspend fun handleDeadLetterDelivery(
        entry: QueueEntry,
        delivered: Int,
        deadLettered: Int,
        onProgress: suspend (FlushProgress) -> Unit,
    ): ReplayOutcome = try {
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            entry.id.sequenceId,
            DeliveryJournal.OUTCOME_DEAD_LETTERED,
        )

        deadLetterQueue.record(
            entry.meta.method,
            entry.meta.url,
            entry.meta.headers,
            entry.body
        )

        if (!completeHeadReplayOrStop(entry.id)) {
            ReplayOutcome.Stop(
                delivered,
                deadLettered,
                persistenceFailed = true
            )
        } else {
            DeliveryJournal.delete(
                queue.fileSystem,
                queuePath = queue.path,
                entry.id.sequenceId
            )
            onProgress(FlushProgress.DeadLettered(entry.id))
            ReplayOutcome.Continue(delivered, deadLettered + 1)
        }
    } catch (e: CancellationException) {
        abortHeadReplayClaimNonCancellable()
        throw e
    } catch (_: Throwable) {
        queue.abortHeadReplayClaim()
        ReplayOutcome.Stop(delivered, deadLettered)
    }

    private suspend fun <T> withReplayClaimRenewal(
        entryId: QueueEntryId,
        block: suspend () -> T,
    ): T = coroutineScope {
        val renewalJob = launch {
            while (isActive) {
                delay(REPLAY_CLAIM_RENEWAL_INTERVAL_MILLIS)
                queue.renewHeadReplayClaim(entryId)
            }
        }
        try {
            block()
        } finally {
            renewalJob.cancelAndJoin()
        }
    }

    /** Cancellation already tripped this coroutine's job, so a plain suspend call to
     * [DiskQueue.abortHeadReplayClaim] would throw immediately without running — clearing the
     * claim here still needs to happen so another process/flush isn't blocked until it goes
     * stale, hence [NonCancellable]. */
    private suspend fun abortHeadReplayClaimNonCancellable() =
        withContext(NonCancellable) { queue.abortHeadReplayClaim() }

    private suspend fun completeHeadReplayOrStop(entryId: QueueEntryId): Boolean = try {
        queue.completeHeadReplay(entryId)
        true
    } catch (e: CancellationException) {
        throw e
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
