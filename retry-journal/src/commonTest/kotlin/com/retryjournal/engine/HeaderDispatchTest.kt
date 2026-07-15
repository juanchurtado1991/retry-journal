package com.retryjournal.engine

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals

class HeaderDispatchTest {

    @Test
    fun `slotFor recognizes Content-Type case-insensitively`() {
        assertEquals(HeaderDispatch.SLOT_CONTENT_TYPE, HeaderDispatch.slotFor(HttpHeaders.ContentType))
        assertEquals(HeaderDispatch.SLOT_CONTENT_TYPE, HeaderDispatch.slotFor("content-type"))
    }

    @Test
    fun `slotFor skips hop-by-hop and body-framing headers on replay`() {
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor(HttpHeaders.ContentLength))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor("CONTENT-LENGTH"))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor(HttpHeaders.TransferEncoding))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor(HttpHeaders.Host))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor(HttpHeaders.Connection))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor("Keep-Alive"))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor("Proxy-Connection"))
        assertEquals(HeaderDispatch.SLOT_SKIP, HeaderDispatch.slotFor("Upgrade"))
    }

    @Test
    fun `slotFor routes unknown headers to OTHER`() {
        assertEquals(HeaderDispatch.SLOT_OTHER, HeaderDispatch.slotFor("Authorization"))
    }
}
