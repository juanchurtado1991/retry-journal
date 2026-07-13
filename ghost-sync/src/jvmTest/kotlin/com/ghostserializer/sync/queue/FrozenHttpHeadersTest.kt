package com.ghostserializer.sync.queue

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrozenHttpHeadersTest {

    @Test
    fun `findValue matches header names case-insensitively`() {
        val headers = FrozenHttpHeaders.of(HttpHeaders.ContentType to "application/json")

        assertEquals("application/json", headers.findValue("content-type"))
        assertEquals("application/json", headers.findValue(HttpHeaders.ContentType))
        assertNull(headers.findValue("X-Missing"))
    }

    @Test
    fun `forEach visits every name-value pair in insertion order`() {
        val headers = FrozenHttpHeaders.of("A" to "1", "B" to "2")
        val seen = mutableListOf<Pair<String, String>>()

        headers.forEach { name, value -> seen.add(name to value) }

        assertEquals(listOf("A" to "1", "B" to "2"), seen)
    }

    @Test
    fun `parallel lists must stay the same length`() {
        assertFailsWith<IllegalArgumentException> {
            FrozenHttpHeaders(listOf("A"), emptyList())
        }
    }
}
