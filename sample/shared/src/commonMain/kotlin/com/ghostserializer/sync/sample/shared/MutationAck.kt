package com.ghostserializer.sync.sample.shared

import com.ghost.serialization.annotations.GhostSerialization

/** The chaos server's happy-path response. */
@GhostSerialization
data class MutationAck(
    val id: String,
    val receivedAtMillis: Long,
)
