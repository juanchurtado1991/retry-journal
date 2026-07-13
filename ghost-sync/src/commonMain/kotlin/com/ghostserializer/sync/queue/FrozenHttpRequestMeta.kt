package com.ghostserializer.sync.queue

import com.ghost.serialization.annotations.GhostSerialization

/**
 * Everything about a queued request except its body. The body travels alongside this record on
 * disk as a raw trailing byte range — it is whatever bytes the caller's `HttpClient` had already
 * produced when the request was made (via Ghost, kotlinx.serialization, or anything else — see
 * [com.ghostserializer.sync.GhostSync] for why the *payload* serializer is never this library's
 * concern), so it is never re-encoded to reach the queue and never Base64-inflated to fit inside
 * this record.
 *
 * `@GhostSerialization` on purpose: this is the one piece of `:ghost-sync` where Ghost's
 * zero-copy encode/decode is worth the dependency — a tiny, extremely hot-path record that every
 * `enqueue()`/`peek()`/compaction touches.
 */
@GhostSerialization
data class FrozenHttpRequestMeta(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val enqueuedAtMillis: Long,
    val attempt: Int,
)
