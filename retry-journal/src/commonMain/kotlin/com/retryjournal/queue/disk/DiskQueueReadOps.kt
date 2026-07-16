package com.retryjournal.queue.disk

import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.record.RecordCodec
import com.retryjournal.queue.record.RecordReadResult
import com.retryjournal.queue.record.RecordScanCodec
import com.retryjournal.queue.record.RecordScanResult
import okio.buffer

/** Reads one live record at a byte offset and validates its sequence id against the index. */
internal object DiskQueueReadOps {

    /** [sequenceId] is the id the in-memory index expects at [offset]; a record that reads back
     * with a *different* sequence id (the index pointing at the wrong offset — recovery bug,
     * manual file tampering, anything) is exactly as untrustworthy as a CRC mismatch, so it's
     * treated the same way: `null`, letting the existing scrub/tombstone path clean it up instead
     * of silently handing a caller the wrong entry's meta/body under the id they asked for. */
    fun readLiveEntryAtLocked(
        queue: DiskQueue,
        sequenceId: Long,
        offset: Long,
    ): QueueEntry? {
        val handle = queue.fileHandles.readHandle()
        val source = handle.source(offset).buffer()
        try {
            return when (val result = RecordCodec.readRecord(source, queue.maxRecordFieldSize)) {
                is RecordReadResult.Live -> if (result.sequenceId == sequenceId) {
                    QueueEntry(QueueEntryId(sequenceId), result.meta, result.body)
                } else {
                    null
                }

                else -> null
            }
        } finally {
            source.close()
        }
    }

    /** Same validity check as [readLiveEntryAtLocked] (CRC + sequence id match), without
     * materializing meta/body or running them through Ghost deserialization — for callers that
     * only need to know *whether* a record is readable (scrub, count, id listing), not its
     * content. Backed by [RecordScanCodec], the same zero-allocation validator crash recovery
     * uses. [scanBuffer]/[scanResult] are caller-owned scratch reused across a whole scan loop —
     * see [DiskQueueRecovery] for the same pattern. */
    fun isLiveEntryReadableAtLocked(
        queue: DiskQueue,
        sequenceId: Long,
        offset: Long,
        scanBuffer: ByteArray,
        scanResult: RecordScanResult,
    ): Boolean {
        val handle = queue.fileHandles.readHandle()
        val source = handle.source(offset).buffer()
        try {
            RecordScanCodec.scanRecord(source, queue.maxRecordFieldSize, scanBuffer, scanResult)
            return scanResult.type == RecordScanResult.TYPE_LIVE && scanResult.sequenceId == sequenceId
        } finally {
            source.close()
        }
    }
}

internal fun DiskQueue.readLiveEntryAtLocked(sequenceId: Long, offset: Long): QueueEntry? =
    DiskQueueReadOps.readLiveEntryAtLocked(this, sequenceId, offset)

internal fun DiskQueue.isLiveEntryReadableAtLocked(
    sequenceId: Long,
    offset: Long,
    scanBuffer: ByteArray,
    scanResult: RecordScanResult,
): Boolean = DiskQueueReadOps.isLiveEntryReadableAtLocked(this, sequenceId, offset, scanBuffer, scanResult)
