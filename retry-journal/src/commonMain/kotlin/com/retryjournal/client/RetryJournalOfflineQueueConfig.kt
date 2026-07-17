package com.retryjournal.client

import com.retryjournal.queue.disk.DiskQueue
import io.ktor.client.request.HttpRequestBuilder

class RetryJournalOfflineQueueConfig {
    lateinit var diskQueue: DiskQueue

    /**
     * Decides whether a request that just failed with a connectivity error gets queued for later
     * delivery. Defaults to [defaultShouldEnqueue] (mutating HTTP methods only — GET/HEAD/OPTIONS
     * are never queued unless overridden).
     *
     * A per-request [RetryJournalHeaders.ENQUEUE_OVERRIDE] header always takes priority over this
     * when present, so most apps never need to touch this property at all — set it only to
     * replace the default rule with your own global policy (e.g. by URL pattern instead of
     * method).
     */
    var shouldEnqueue: (HttpRequestBuilder) -> Boolean = defaultShouldEnqueue
}
