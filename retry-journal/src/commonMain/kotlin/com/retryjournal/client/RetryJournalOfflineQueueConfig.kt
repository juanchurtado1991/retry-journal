package com.retryjournal.client

import com.retryjournal.queue.disk.DiskQueue
import io.ktor.client.request.HttpRequestBuilder

class RetryJournalOfflineQueueConfig {
    lateinit var diskQueue: DiskQueue

    /**
     * Decides whether a request *would* get queued if it failed with a connectivity error.
     * Defaults to [defaultShouldEnqueue] (mutating HTTP methods only — GET/HEAD/OPTIONS are never
     * queued unless overridden).
     *
     * A per-request [RetryJournalHeaders.ENQUEUE_OVERRIDE] header always takes priority over this
     * when present, so most apps never need to touch this property at all — set it only to
     * replace the default rule with your own global policy (e.g. by URL pattern instead of
     * method).
     *
     * Called before *every* send attempt — including ones that end up succeeding — not only ones
     * that fail, because the header-strip step it's paired with has to run before the request is
     * sent regardless of outcome. Keep it cheap and free of side effects; if it throws, the
     * request is still sent normally and the default rule is used instead of this one.
     */
    var shouldEnqueue: (HttpRequestBuilder) -> Boolean = defaultShouldEnqueue
}
