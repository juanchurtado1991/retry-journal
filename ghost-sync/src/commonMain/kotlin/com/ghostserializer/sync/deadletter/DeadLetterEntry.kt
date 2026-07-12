package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.FrozenHttpRequestMeta

/** A request that was rejected by the server (4xx) and parked for manual inspection or retry. */
data class DeadLetterEntry(
    val id: DeadLetterEntryId,
    val meta: FrozenHttpRequestMeta,
    val body: ByteArray,
)
