package com.ghostserializer.sync.queue.disk

import com.ghostserializer.sync.queue.QueueEntry
import com.ghostserializer.sync.queue.QueueEntryId
import com.ghostserializer.sync.queue.record.RecordCodec
import com.ghostserializer.sync.queue.record.RecordReadResult
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
}

internal fun DiskQueue.readLiveEntryAtLocked(sequenceId: Long, offset: Long): QueueEntry? =
    DiskQueueReadOps.readLiveEntryAtLocked(this, sequenceId, offset)
