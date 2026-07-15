package com.retryjournal.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QueueEntryTest {

    private fun entry(body: ByteArray = "payload".encodeToByteArray()) = QueueEntry(
        id = QueueEntryId(1L),
        meta = FrozenHttpRequestMeta(
            method = "POST",
            url = "https://example.com/a",
            headers = FrozenHttpHeaders.of("X" to "1"),
            enqueuedAtMillis = 1L,
        ),
        body = body,
    )

    @Test
    fun `equals compares body bytes by value not reference`() {
        val left = entry("same".encodeToByteArray())
        val right = entry("same".encodeToByteArray())
        assertTrue(left == right)
        assertEquals(left.hashCode(), right.hashCode())
    }

    @Test
    fun `equals rejects different body content`() {
        assertFalse(entry("a".encodeToByteArray()) == entry("b".encodeToByteArray()))
    }

    @Test
    fun `equals rejects non-QueueEntry values`() {
        assertFalse(entry().equals("not-an-entry"))
    }

    @Test
    fun `toString reports body size instead of array identity`() {
        val text = entry().toString()
        assertTrue(text.contains("body.size=7"))
        assertFalse(text.contains("[B@"))
    }

    @Test
    fun `identity equals short-circuits`() {
        val value = entry()
        assertTrue(value == value)
    }

    @Test
    fun `hashCode differs when ids differ`() {
        val other = entry().copy(id = QueueEntryId(2L))
        assertNotEquals(entry().hashCode(), other.hashCode())
    }
}
