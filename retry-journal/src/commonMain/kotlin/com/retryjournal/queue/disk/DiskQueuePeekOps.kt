package com.retryjournal.queue.disk

import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.record.PackedIndexEntry
import com.retryjournal.queue.record.RecordScanResult
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

    /** Only the ids are returned — a CRC-only validity check (see [DiskQueue.isLiveEntryReadableAtLocked])
     * is enough, no need to materialize/deserialize meta or body for entries whose content nobody asked for. */
    fun collectReadableEntryIdsLocked(
        queue: DiskQueue,
        limit: Int,
        outResult: MutableCollection<QueueEntryId>,
    ): Int {
        val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
        val scanResult = RecordScanResult()
        var count = 0
        for (sequenceId in queue.liveOffsetsBySequence.keys) {
            if (count >= limit) {
                break
            }
            val packed = queue.liveOffsetsBySequence[sequenceId] ?: continue
            if (queue.isLiveEntryReadableAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed), scanBuffer, scanResult)) {
                outResult.add(QueueEntryId(sequenceId))
                count++
            }
        }
        return count
    }

    /** A plain count — same reasoning as [collectReadableEntryIdsLocked] for using the
     * CRC-only check instead of a full decode. */
    fun countReadableEntriesLocked(queue: DiskQueue): Int {
        val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
        val scanResult = RecordScanResult()
        var count = 0
        for ((sequenceId, packed) in queue.liveOffsetsBySequence) {
            if (queue.isLiveEntryReadableAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed), scanBuffer, scanResult)) {
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
