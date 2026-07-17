package com.retryjournal.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod

/**
 * Default [RetryJournalOfflineQueueConfig.shouldEnqueue] rule: only mutating methods are queued
 * when they fail offline. A GET/HEAD/OPTIONS that fails has no caller left waiting for its
 * response by the time a delayed `flush()` eventually resends it days later — queueing it just
 * spends disk space and a queue slot for a reply nobody will ever read. POST/PUT/PATCH/DELETE are
 * requests the user asked to happen; those need to be delivered even if delayed.
 *
 * Compares [HttpRequestBuilder.method]'s value case-insensitively rather than the [HttpMethod]
 * instance directly — `HttpMethod` compares by exact string, and Ktor's own `post()`/`put()`/etc.
 * DSL functions always assign the canonical uppercase instances, but a request built by directly
 * assigning `method = HttpMethod("post")` (bypassing that DSL — some codegen/config-driven clients
 * do) would otherwise silently fall through to "don't queue" instead of matching.
 */
public val defaultShouldEnqueue: (HttpRequestBuilder) -> Boolean = { request ->
    request.method.value.uppercase() in MUTATING_METHOD_NAMES
}

private val MUTATING_METHOD_NAMES: Set<String> = setOf("POST", "PUT", "PATCH", "DELETE")
