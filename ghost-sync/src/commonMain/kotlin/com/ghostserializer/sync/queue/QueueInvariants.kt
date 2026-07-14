package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.disk.readLiveEntryAtLocked
import com.ghostserializer.sync.queue.platform.currentTimeMillis
import com.ghostserializer.sync.queue.record.PackedIndexEntry

/** Verifiable queue invariants — called from tests after mutating operations. */
internal object QueueInvariants {

    fun assertHoldLocked(queue: DiskQueue) {
        val liveIds = queue.liveOffsetsBySequence.keys
        val headSequenceId = liveIds.firstOrNull()
        val size = countReadableLocked(queue)
        require(size == liveIds.size) {
            "size mismatch: counted $size readable entries but index has ${liveIds.size}"
        }
        if (headSequenceId != null) {
            val claim = ReplayClaim.hasNonStaleClaim(
                queue.fileSystem,
                ReplayClaim.claimPath(queue.path),
                currentTimeMillis(),
            )
            if (claim != null && liveIds.contains(claim.sequenceId)) {
                require(claim.sequenceId == headSequenceId) {
                    "blocking replay claim targets sequence ${claim.sequenceId} but head is $headSequenceId"
                }
            }
        }
        DeliveryJournal.assertNoStaleJournalsLocked(queue, headSequenceId)
    }

    private fun countReadableLocked(queue: DiskQueue): Int {
        var count = 0
        for (sequenceId in queue.liveOffsetsBySequence.keys) {
            val packed = queue.liveOffsetsBySequence.getValue(sequenceId)
            val offset = PackedIndexEntry.unpackOffset(packed)
            if (queue.readLiveEntryAtLocked(sequenceId, offset) != null) {
                count++
            }
        }
        return count
    }
}
