package com.ghostserializer.sync.queue.record

/**
 * A lighter-weight read of one record's framing, used only by
 * [DiskQueue][com.ghostserializer.sync.queue.disk.DiskQueue]'s crash-recovery scan. That scan needs
 * the sequence id and how many bytes to skip to reach the next record — never the meta/body
 * content itself — so [RecordScanCodec.scanRecord] never materializes them, unlike [RecordReadResult]
 * (returned by [RecordCodec.readRecord], which
 * [DiskQueue.peek][com.ghostserializer.sync.queue.disk.DiskQueue.peek]/`get`/compaction use and does
 * need the content for).
 *
 * Refactored to a single mutable carrier to achieve zero heap allocation during recovery scans.
 */
internal class RecordScanResult {
    var type: Int = TYPE_EOF
    var sequenceId: Long = 0L
    var recordLength: Int = 0

    companion object {
        const val TYPE_LIVE: Int = 1
        const val TYPE_TOMBSTONE: Int = 2
        const val TYPE_INVALID: Int = 3
        const val TYPE_EOF: Int = 4
    }
}
