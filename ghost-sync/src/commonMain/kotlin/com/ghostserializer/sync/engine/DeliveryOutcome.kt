package com.ghostserializer.sync.engine

/** Recorded server-side effect while local queue removal is still pending. */
internal enum class DeliveryOutcome {
    Delivered,
    DeadLettered,
}
