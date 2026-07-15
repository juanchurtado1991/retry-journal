package com.retryjournal.queue.disk

import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.record.PackedIndexEntry
import kotlin.collections.iterator

/** Read-side iteration helpers for [DiskQueue.peekAll], [DiskQueue.peekIds], and [DiskQueue.peekAllRaw]. */
internal object DiskQueuePeekOps {

    fun appendAllReadableEntriesLocked(
        queue: DiskQueue,
        outResult: MutableCollection<QueueEntry>,
    ): Int {
        val before = outResult.size
        forEachReadableEntryLocked(queue) { _, entry -> outResult.add(entry) }
        return outResult.size - before
    }

    inline fun forEachReadableEntryLocked(
        queue: DiskQueue,
        action: (Long, QueueEntry) -> Unit,
    ) {
        for ((sequenceId, packed) in queue.liveOffsetsBySequence) {
            queue.readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed))
                ?.let { entry -> action(sequenceId, entry) }
        }
    }

    fun collectReadableEntryIdsLocked(
        queue: DiskQueue,
        limit: Int,
        outResult: MutableCollection<QueueEntryId>,
    ): Int {
        var count = 0
        for (sequenceId in queue.liveOffsetsBySequence.keys) {
            if (count >= limit) {
                break
            }
            val packed = queue.liveOffsetsBySequence[sequenceId] ?: continue
            if (queue.readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed)) != null) {
                outResult.add(QueueEntryId(sequenceId))
                count++
            }
        }
        return count
    }

    fun countReadableEntriesLocked(queue: DiskQueue): Int {
        var count = 0
        for ((sequenceId, packed) in queue.liveOffsetsBySequence) {
            if (queue.readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed)) != null) {
                count++
            }
        }
        return count
    }
}

internal fun DiskQueue.appendAllReadableEntriesLocked(outResult: MutableCollection<QueueEntry>): Int =
    DiskQueuePeekOps.appendAllReadableEntriesLocked(this, outResult)

internal inline fun DiskQueue.forEachReadableEntryLocked(action: (Long, QueueEntry) -> Unit) =
    DiskQueuePeekOps.forEachReadableEntryLocked(this, action)

internal fun DiskQueue.collectReadableEntryIdsLocked(
    limit: Int,
    outResult: MutableCollection<QueueEntryId>,
): Int = DiskQueuePeekOps.collectReadableEntryIdsLocked(this, limit, outResult)

internal fun DiskQueue.countReadableEntriesLocked(): Int =
    DiskQueuePeekOps.countReadableEntriesLocked(this)
