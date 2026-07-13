package com.ghostserializer.sync.engine

import io.ktor.http.HttpHeaders

/** Case-insensitive header-name dispatch for replay — no [Map], just a compare chain. */
internal object HeaderDispatch {
    const val SLOT_OTHER: Int = 0
    const val SLOT_CONTENT_TYPE: Int = 1
    const val SLOT_CONTENT_LENGTH: Int = 2

    fun slotFor(name: String): Int {
        if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
            return SLOT_CONTENT_TYPE
        }
        if (name.equals(HttpHeaders.ContentLength, ignoreCase = true)) {
            return SLOT_CONTENT_LENGTH
        }
        return SLOT_OTHER
    }
}
