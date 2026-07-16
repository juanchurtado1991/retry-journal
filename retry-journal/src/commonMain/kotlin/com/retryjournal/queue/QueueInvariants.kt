package com.retryjournal.queue

import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.readLiveEntryAtLocked
import com.retryjournal.queue.platform.currentTimeMillis
import com.retryjournal.queue.record.PackedIndexEntry

/** Verifiable queue invariants — called from tests after mutating operations. */
internal object QueueInvariants {

    fun assertHoldLocked(queue: DiskQueue) {
        val headSequenceId = queue.liveOffsetsBySequence.firstSequenceIdOrNull()
        val indexSize = queue.liveOffsetsBySequence.size
        val size = countReadableLocked(queue)
        require(size == indexSize) {
            "size mismatch: counted $size readable entries but index has $indexSize"
        }
        if (headSequenceId != null) {
            val claim = ReplayClaim.hasNonStaleClaim(
                queue.fileSystem,
                ReplayClaim.claimPath(queue.path),
                currentTimeMillis(),
            )
            if (claim != null && queue.liveOffsetsBySequence.containsKey(claim.sequenceId)) {
                require(claim.sequenceId == headSequenceId) {
                    "blocking replay claim targets sequence ${claim.sequenceId} but head is $headSequenceId"
                }
            }
        }
        DeliveryJournal.assertNoStaleJournalsLocked(queue, headSequenceId)
    }

    private fun countReadableLocked(queue: DiskQueue): Int {
        var count = 0
        queue.liveOffsetsBySequence.forEach { sequenceId, packed ->
            val offset = PackedIndexEntry.unpackOffset(packed)
            if (queue.readLiveEntryAtLocked(sequenceId, offset) != null) {
                count++
            }
        }
        return count
    }
}
