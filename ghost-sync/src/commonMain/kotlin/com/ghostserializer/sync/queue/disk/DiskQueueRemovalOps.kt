package com.ghostserializer.sync.queue.disk

import com.ghostserializer.sync.queue.ReplayClaim
import com.ghostserializer.sync.queue.record.PackedIndexEntry
import com.ghostserializer.sync.queue.record.RecordCodec
import okio.IOException

/** Durable tombstone appends and truncate rollback for failed removals. */
internal object DiskQueueRemovalOps {

    fun removeLocked(queue: DiskQueue, targetSequenceId: Long) {
        val packed = queue.liveOffsetsBySequence[targetSequenceId] ?: return
        val removedLength = PackedIndexEntry.unpackLength(packed)
        val offsetBefore = queue.fileLength

        val sink = queue.fileHandles.appendSink()
        try {
            val tombstoneSize = RecordCodec.writeTombstone(sink, targetSequenceId)
            sink.flush()

            queue.liveOffsetsBySequence.remove(targetSequenceId)
            queue.fileLength += tombstoneSize
            queue.deadBytes += removedLength + DiskQueueConstants.TOMBSTONE_RECORD_SIZE
        } catch (e: IOException) {
            queue.fileHandles.closeAppendSink()
            truncateFileLocked(queue, offsetBefore)
            throw e
        }
    }

    fun truncateFileLocked(queue: DiskQueue, offset: Long) {
        if (!queue.fileSystem.exists(queue.path)) {
            return
        }
        val handle = queue.fileSystem.openReadWrite(queue.path)
        try {
            handle.resize(offset)
        } finally {
            handle.close()
        }
        queue.fileLength = offset
    }

    /** Rejects [DiskQueue.remove] while another process holds an active replay claim on this id. */
    fun assertNotClaimedForReplay(queue: DiskQueue, targetSequenceId: Long) {
        val claimPath = ReplayClaim.claimPath(queue.path)
        ReplayClaim.clearIfStale(queue.fileSystem, claimPath)
        val claim = ReplayClaim.read(queue.fileSystem, claimPath) ?: return
        if (claim.sequenceId == targetSequenceId) {
            error(DiskQueueConstants.REMOVE_WHILE_CLAIMED_MESSAGE)
        }
    }
}

internal fun DiskQueue.removeLocked(targetSequenceId: Long) =
    DiskQueueRemovalOps.removeLocked(this, targetSequenceId)

internal fun DiskQueue.truncateFileLocked(offset: Long) =
    DiskQueueRemovalOps.truncateFileLocked(this, offset)

internal fun DiskQueue.assertNotClaimedForRemoval(targetSequenceId: Long) =
    DiskQueueRemovalOps.assertNotClaimedForReplay(this, targetSequenceId)
