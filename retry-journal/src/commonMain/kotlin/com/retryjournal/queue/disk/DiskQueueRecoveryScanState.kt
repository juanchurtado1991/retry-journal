package com.retryjournal.queue.disk

/** Mutable scan cursor while [DiskQueueRecovery] walks the queue file byte-by-byte. */
internal class DiskQueueRecoveryScanState(
    val liveOffsetsBySequence: LinkedHashMap<Long, Long> = LinkedHashMap(),
    var nextSequenceId: Long = 0L,
    var deadBytes: Long = 0L,
    var offset: Long = 0L,
    var lastValidOffset: Long = 0L,
)
