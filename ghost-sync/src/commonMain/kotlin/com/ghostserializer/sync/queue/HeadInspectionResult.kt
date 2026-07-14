package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.disk.finalizeHeadScrubIfNeededLocked
import com.ghostserializer.sync.queue.disk.scanFirstReadableHeadLocked

/** Atomic head snapshot for [com.ghostserializer.sync.engine.HeadReplayExecutor.resolveHeadState]. */
internal data class HeadInspectionResult(
    val entry: QueueEntry?,
    val blockedByClaim: Boolean,
    val journalOutcome: String?,
)

internal fun DiskQueue.inspectHeadLocked(): HeadInspectionResult {
    DeliveryJournal.migrateLegacyJournalIfPresent(fileSystem, path)
    val scan = scanFirstReadableHeadLocked()
    finalizeHeadScrubIfNeededLocked(scan.removedAny)
    val entry = scan.entry
    val journalOutcome = entry?.let { head ->
        when (val read = DeliveryJournal.read(fileSystem, path, head.id.sequenceId)) {
            is DeliveryJournalReadResult.Valid -> read.outcome
            is DeliveryJournalReadResult.CorruptPending -> read.outcome
            else -> null
        }
    }
    val blockedByClaim = when {
        entry == null -> isHeadBlockedByActiveClaimLocked()
        journalOutcome != null -> false
        else -> isHeadBlockedByActiveClaimLocked()
    }
    return HeadInspectionResult(entry, blockedByClaim, journalOutcome)
}
