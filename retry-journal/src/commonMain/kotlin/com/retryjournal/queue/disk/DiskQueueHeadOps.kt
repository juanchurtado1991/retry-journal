package com.retryjournal.queue.disk

import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.HeadScanResult
import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.platform.currentTimeMillis
import com.retryjournal.queue.record.PackedIndexEntry
import okio.Path

/** Head scanning and cross-process [com.retryjournal.queue.ReplayClaim] coordination for [DiskQueue]. */
internal object DiskQueueHeadOps {

    fun scanFirstReadableHeadLocked(queue: DiskQueue): HeadScanResult {
        val claimPath = ReplayClaim.claimPath(queue.path)
        var result: QueueEntry? = null
        var removedAny = false
        while (true) {
            val (sequenceId, packed) = queue.liveOffsetsBySequence.entries.firstOrNull() ?: break
            val entry = queue.readLiveEntryAtLocked(
                sequenceId,
                PackedIndexEntry.unpackOffset(packed),
            )
            if (entry != null) {
                result = entry
                break
            }
            if (ReplayClaim.isActiveClaimForSequence(queue.fileSystem, claimPath, sequenceId)) {
                break
            }
            queue.removeLocked(sequenceId)
            removedAny = true
        }
        return HeadScanResult(result, removedAny)
    }

    fun finalizeScrubIfNeededLocked(queue: DiskQueue, removedAny: Boolean) {
        if (!removedAny) {
            return
        }
        queue.compactIfNeededLocked()
        DiskQueueIndexSync.bumpGenerationLocked(queue)
    }

    fun claimHeadForReplay(
        queue: DiskQueue,
        entry: QueueEntry,
        claimPath: Path,
    ): HeadReplayPrepareResult {
        val nowMillis = currentTimeMillis()
        ReplayClaim.clearIfStale(queue.fileSystem, claimPath)
        val activeClaim = ReplayClaim.hasNonStaleClaim(queue.fileSystem, claimPath, nowMillis)
        if (activeClaim != null) {
            if (!queue.liveOffsetsBySequence.containsKey(activeClaim.sequenceId)) {
                ReplayClaim.delete(queue.fileSystem, claimPath)
            } else {
                val headSequenceId = queue.liveOffsetsBySequence.keys.firstOrNull()
                if (headSequenceId != activeClaim.sequenceId) {
                    ReplayClaim.delete(queue.fileSystem, claimPath)
                } else if (DeliveryJournal.hasPendingForSequence(
                        queue.fileSystem,
                        queue.path,
                        entry.id.sequenceId,
                    )
                ) {
                    ReplayClaim.delete(queue.fileSystem, claimPath)
                } else {
                    return HeadReplayPrepareResult.HeadBlocked
                }
            }
        }

        ReplayClaim.write(queue.fileSystem, claimPath, entry.id.sequenceId, nowMillis)
        return HeadReplayPrepareResult.Ready(entry)
    }

    fun validateCompleteHeadReplayLocked(
        queue: DiskQueue,
        entryId: QueueEntryId,
        claimPath: Path,
    ) {
        val activeClaim = ReplayClaim.read(queue.fileSystem, claimPath)
            ?: error(DiskQueueConstants.COMPLETE_HEAD_CLAIM_MISSING_MESSAGE)
        if (ReplayClaim.isStale(activeClaim, currentTimeMillis())) {
            error(DiskQueueConstants.COMPLETE_HEAD_CLAIM_STALE_MESSAGE)
        }
        if (activeClaim.sequenceId != entryId.sequenceId) {
            error(DiskQueueConstants.COMPLETE_HEAD_CLAIM_MISMATCH_MESSAGE)
        }
        val headSequenceId = queue.liveOffsetsBySequence.keys.firstOrNull()
        if (headSequenceId != entryId.sequenceId) {
            error(DiskQueueConstants.COMPLETE_HEAD_NOT_HEAD_MESSAGE)
        }
    }

    fun isHeadBlockedByActiveClaimLocked(queue: DiskQueue): Boolean =
        hasBlockingReplayClaimLocked(queue)

    fun hasBlockingReplayClaimLocked(queue: DiskQueue): Boolean {
        val claim = ReplayClaim.hasNonStaleClaim(
            queue.fileSystem,
            ReplayClaim.claimPath(queue.path),
        ) ?: return false
        if (!queue.liveOffsetsBySequence.containsKey(claim.sequenceId)) {
            return false
        }
        val headSequenceId = queue.liveOffsetsBySequence.keys.firstOrNull() ?: return false
        return headSequenceId == claim.sequenceId
    }
}

internal fun DiskQueue.scanFirstReadableHeadLocked(): HeadScanResult =
    DiskQueueHeadOps.scanFirstReadableHeadLocked(this)

internal fun DiskQueue.finalizeHeadScrubIfNeededLocked(removedAny: Boolean) =
    DiskQueueHeadOps.finalizeScrubIfNeededLocked(this, removedAny)

internal fun DiskQueue.claimHeadForReplay(entry: QueueEntry, claimPath: Path): HeadReplayPrepareResult =
    DiskQueueHeadOps.claimHeadForReplay(this, entry, claimPath)

internal fun DiskQueue.validateCompleteHeadReplayLocked(entryId: QueueEntryId, claimPath: Path) =
    DiskQueueHeadOps.validateCompleteHeadReplayLocked(this, entryId, claimPath)
