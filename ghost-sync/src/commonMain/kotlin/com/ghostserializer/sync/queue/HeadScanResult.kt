package com.ghostserializer.sync.queue

/** Outcome of [com.ghostserializer.sync.queue.disk.DiskQueue]'s internal head scan — the first readable entry, if any, and whether
 * corrupt/unreadable heads were tombstoned along the way. */
internal data class HeadScanResult(
    val entry: QueueEntry?,
    val removedAny: Boolean,
)
