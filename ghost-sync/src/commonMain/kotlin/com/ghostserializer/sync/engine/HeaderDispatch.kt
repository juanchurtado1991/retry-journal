package com.ghostserializer.sync.engine

import io.ktor.http.HttpHeaders

/** Case-insensitive header-name dispatch for replay — no [Map], just a compare chain. */
internal object HeaderDispatch {
    const val SLOT_OTHER: Int = 0
    const val SLOT_CONTENT_TYPE: Int = 1
    const val SLOT_SKIP: Int = 2

    fun slotFor(name: String): Int {
        if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
            return SLOT_CONTENT_TYPE
        }
        if (shouldSkipOnReplay(name)) {
            return SLOT_SKIP
        }
        return SLOT_OTHER
    }

    /** Hop-by-hop / framing headers from capture are invalid once the body is a byte array. */
    private fun shouldSkipOnReplay(name: String): Boolean =
        name.equals(HttpHeaders.ContentLength, ignoreCase = true) ||
            name.equals(HttpHeaders.TransferEncoding, ignoreCase = true) ||
            name.equals(HttpHeaders.Host, ignoreCase = true) ||
            name.equals(HttpHeaders.Connection, ignoreCase = true) ||
            name.equals("TE", ignoreCase = true) ||
            name.equals(HttpHeaders.Trailer, ignoreCase = true)
}
