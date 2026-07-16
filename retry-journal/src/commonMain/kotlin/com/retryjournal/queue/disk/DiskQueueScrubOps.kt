package com.retryjournal.queue.disk

import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.record.PackedIndexEntry
import com.retryjournal.queue.record.RecordScanResult
import kotlin.collections.iterator

/** Tombstones corrupt index slots so [DiskQueue] cannot stall behind unreadable records. */
internal object DiskQueueScrubOps {

    fun scrubUnreadableEntriesLocked(queue: DiskQueue) {
        collectUnreadableSequenceIdsLocked(queue)
        removeCollectedSequenceIdsLocked(queue)
        if (queue.scrubScratchCount > 0) {
            queue.compactIfNeededLocked()
            DiskQueueIndexSync.bumpGenerationLocked(queue)
        }
    }

    private fun collectUnreadableSequenceIdsLocked(queue: DiskQueue) {
        val claimPath = ReplayClaim.claimPath(queue.path)
        // A CRC-only validity check (RecordScanCodec, via isLiveEntryReadableAtLocked) instead of
        // a full readLiveEntryAtLocked — this only needs to know whether each entry is readable,
        // never its meta/body content, so there's no reason to pay for materializing and
        // Ghost-deserializing every record in the queue just to decide what to scrub.
        val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
        val scanResult = RecordScanResult()
        queue.scrubScratchCount = 0
        for ((sequenceId, packed) in queue.liveOffsetsBySequence) {
            if (queue.isLiveEntryReadableAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed), scanBuffer, scanResult)) {
                continue
            }
            if (ReplayClaim.isActiveClaimForSequence(queue.fileSystem, claimPath, sequenceId)) {
                continue
            }
            ensureScrubScratchCapacity(queue, queue.scrubScratchCount + 1)
            queue.scrubScratch[queue.scrubScratchCount++] = sequenceId
        }
    }

    private fun ensureScrubScratchCapacity(queue: DiskQueue, capacity: Int) {
        if (queue.scrubScratch.size >= capacity) {
            return
        }
        queue.scrubScratch = queue.scrubScratch.copyOf(queue.scrubScratch.size shl 1)
    }

    private fun removeCollectedSequenceIdsLocked(queue: DiskQueue) {
        for (index in 0 until queue.scrubScratchCount) {
            queue.removeLocked(queue.scrubScratch[index])
        }
    }
}

internal fun DiskQueue.scrubUnreadableEntriesLocked() =
    DiskQueueScrubOps.scrubUnreadableEntriesLocked(this)
