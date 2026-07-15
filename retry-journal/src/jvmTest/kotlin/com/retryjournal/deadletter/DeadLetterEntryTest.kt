package com.retryjournal.deadletter

import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeadLetterEntryTest {

    private fun entry(body: ByteArray = "payload".encodeToByteArray()) = DeadLetterEntry(
        id = DeadLetterEntryId(9L),
        meta = FrozenHttpRequestMeta(
            method = "POST",
            url = "https://example.com/rejected",
            headers = FrozenHttpHeaders.EMPTY,
            enqueuedAtMillis = 0L,
        ),
        body = body,
    )

    @Test
    fun `equals compares body bytes by value`() {
        val left = entry("same".encodeToByteArray())
        val right = entry("same".encodeToByteArray())
        assertTrue(left == right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun `equals rejects different ids`() {
        assertFalse(entry() == entry().copy(id = DeadLetterEntryId(10L)))
    }

    @Test
    fun `toString reports body size`() {
        assertTrue(entry().toString().contains("body.size=7"))
    }
}
