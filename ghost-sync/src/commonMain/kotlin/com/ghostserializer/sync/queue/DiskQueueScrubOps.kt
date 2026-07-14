package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.record.PackedIndexEntry

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
        queue.scrubScratchCount = 0
        for ((sequenceId, packed) in queue.liveOffsetsBySequence) {
            if (queue.readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed)) != null) {
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
