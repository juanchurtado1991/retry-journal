package com.ghostserializer.sync.sample.shared

import com.ghost.serialization.annotations.GhostSerialization

/** What the stress-test screen enqueues offline by the thousand. */
@GhostSerialization
data class MutationRequest(
    val id: String,
    val payload: String,
    val createdAtMillis: Long,
)
