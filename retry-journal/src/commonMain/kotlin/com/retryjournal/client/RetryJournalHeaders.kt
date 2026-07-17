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
 * // Ktorfit — ENQUEUE_ON_FAILURE / DISCARD_ON_FAILURE are the full "Name: Value" line @Headers expects
 * interface Api {
 *     @Headers(RetryJournalHeaders.DISCARD_ON_FAILURE)
 *     @POST("analytics-ping")
 *     suspend fun ping()
 * }
 * ```
 */
object RetryJournalHeaders {
    const val ENQUEUE_OVERRIDE: String = "X-Retry-Journal-Enqueue"

    /** Full `"Name: Value"` line for codegen clients whose `@Headers` annotation takes a literal
     * header string (Ktorfit, Retrofit-style) rather than a name/value pair — pass this directly,
     * e.g. `@Headers(RetryJournalHeaders.ENQUEUE_ON_FAILURE)`. */
    const val ENQUEUE_ON_FAILURE: String = "$ENQUEUE_OVERRIDE: true"

    /** Same as [ENQUEUE_ON_FAILURE] but for the opposite override — skip queueing this endpoint
     * even if it would otherwise be queued (e.g. a mutating method under the default rule). */
    const val DISCARD_ON_FAILURE: String = "$ENQUEUE_OVERRIDE: false"
}
