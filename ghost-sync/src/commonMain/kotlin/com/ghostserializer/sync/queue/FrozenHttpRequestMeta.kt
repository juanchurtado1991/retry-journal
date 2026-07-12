package com.ghostserializer.sync.queue

import com.ghost.serialization.annotations.GhostSerialization

/**
 * Everything about a queued request except its body. The body travels alongside this record on
 * disk as a raw trailing byte range — it is already Ghost-serialized JSON produced by
 * `GhostContentConverter` when the request was made, so it is never re-encoded to reach the
 * queue and never Base64-inflated to fit inside this JSON envelope.
 */
@GhostSerialization
data class FrozenHttpRequestMeta(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val enqueuedAtMillis: Long,
    val attempt: Int,
)
