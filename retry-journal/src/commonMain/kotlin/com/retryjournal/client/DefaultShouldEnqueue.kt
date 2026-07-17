package com.retryjournal.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod

/**
 * Default [RetryJournalOfflineQueueConfig.shouldEnqueue] rule: only mutating methods are queued
 * when they fail offline. A GET/HEAD/OPTIONS that fails has no caller left waiting for its
 * response by the time a delayed `flush()` eventually resends it days later — queueing it just
 * spends disk space and a queue slot for a reply nobody will ever read. POST/PUT/PATCH/DELETE are
 * requests the user asked to happen; those need to be delivered even if delayed.
 */
public val defaultShouldEnqueue: (HttpRequestBuilder) -> Boolean = { request ->
    request.method in MUTATING_METHODS
}

private val MUTATING_METHODS: Set<HttpMethod> = setOf(
    HttpMethod.Post,
    HttpMethod.Put,
    HttpMethod.Patch,
    HttpMethod.Delete,
)
