package com.ghostserializer.sync.queue

/** Rebuilt index state produced by [DiskQueueRecovery.recover]. */
internal class DiskQueueRecoveryResult(
    val liveOffsetsBySequence: LinkedHashMap<Long, Long>,
    val nextSequenceId: Long,
    val deadBytes: Long,
    val fileLength: Long,
)
