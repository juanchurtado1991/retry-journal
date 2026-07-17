package com.retryjournal.client

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultShouldEnqueueTest {

    @Test
    fun `canonical uppercase mutating methods are queued`() {
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Post }))
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Put }))
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Patch }))
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Delete }))
    }

    @Test
    fun `canonical uppercase safe methods are not queued`() {
        assertFalse(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Get }))
        assertFalse(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Head }))
        assertFalse(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod.Options }))
    }

    /** Regression: comparing the [HttpMethod] instance directly (exact-string `equals`) instead of
     * its value case-insensitively silently treated a non-canonical-case method — built by
     * assigning `method = HttpMethod("post")` directly instead of going through Ktor's `post()`
     * DSL — as "don't queue," even though it's a mutating request. */
    @Test
    fun `a non-canonical-case method built directly still matches by value`() {
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod("post") }))
        assertTrue(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod("Delete") }))
        assertFalse(defaultShouldEnqueue(HttpRequestBuilder().apply { method = HttpMethod("get") }))
    }
}
