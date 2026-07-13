package com.ghostserializer.sync.queue

/**
 * A lighter-weight read of one record's framing, used only by [DiskQueue]'s crash-recovery scan.
 * That scan needs the sequence id and how many bytes to skip to reach the next record — never the
 * meta/body content itself — so [RecordCodec.scanRecord] never materializes them, unlike
 * [RecordReadResult] (returned by [RecordCodec.readRecord], which [DiskQueue.peek]/`get`/
 * compaction use and does need the content for).
 */
internal sealed class RecordScanResult {
    data class Live(val sequenceId: Long, val recordLength: Int) : RecordScanResult()
    data class Tombstone(val targetSequenceId: Long, val recordLength: Int) : RecordScanResult()
    object Invalid : RecordScanResult()
    object EndOfFile : RecordScanResult()
}
