package com.ghostserializer.sync.queue

/** A live, decoded record read back from a [DiskQueue]. */
data class QueueEntry(
    val id: QueueEntryId,
    val meta: FrozenHttpRequestMeta,
    val body: ByteArray,
)
