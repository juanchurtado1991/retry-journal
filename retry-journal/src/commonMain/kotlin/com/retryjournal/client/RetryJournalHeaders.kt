package com.retryjournal.client

/**
 * Per-request override for whether a connectivity failure gets queued for offline delivery. Set
 * [ENQUEUE_OVERRIDE] to `"true"`/`"false"` and [RetryJournalOfflineQueuePlugin] strips it before
 * the request is ever sent — it is a signal for this plugin alone, never part of the real request
 * the server sees, and never needs filtering before persistence or replay because it is already
 * gone by the time either of those would see it.
 *
 * Exists specifically for callers going through a codegen client (Ktorfit, or any Retrofit-style
 * annotation interface) that never touch [io.ktor.client.request.HttpRequestBuilder] directly and
 * so have no way to reach [RetryJournalOfflineQueueConfig.shouldEnqueue]'s per-call escape hatch
 * any other way — headers are universally reachable through `@Headers`/`@Header` annotations.
 *
 * Takes priority over [RetryJournalOfflineQueueConfig.shouldEnqueue] and the default rule
 * ([defaultShouldEnqueue]) when present and parses as exactly `"true"` or `"false"`; any other
 * value is ignored and falls through to those.
 *
 * ```kotlin
 * // Plain Ktor client
 * client.post("https://api.example.com/analytics-ping") {
 *     header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "false")
 * }
 *
 * // Ktorfit
 * interface Api {
 *     @Headers(["X-Retry-Journal-Enqueue: false"])
 *     @POST("analytics-ping")
 *     suspend fun ping()
 * }
 * ```
 */
public object RetryJournalHeaders {
    public const val ENQUEUE_OVERRIDE: String = "X-Retry-Journal-Enqueue"
}
