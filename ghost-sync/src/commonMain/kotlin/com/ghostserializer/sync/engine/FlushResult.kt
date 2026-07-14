package com.ghostserializer.sync.engine

/**
 * [stoppedEarly] is true when a 5xx or a network failure interrupted the loop with work still
 * left in the queue — the caller's scheduler decides when to call `flush()` again. It is false
 * when the queue was fully drained (every remaining entry either delivered or dead-lettered).
 *
 * [persistenceFailed] is true when the server accepted a replay (2xx) or a dead-letter was
 * durably recorded but local removal did not finish — a [DeliveryJournal] was written so the
 * next `flush()` retries removal without re-sending HTTP.
 */
data class FlushResult(
    val delivered: Int,
    val deadLettered: Int,
    val stoppedEarly: Boolean,
    val persistenceFailed: Boolean = false,
)
