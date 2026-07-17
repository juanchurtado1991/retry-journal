package com.retryjournal.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** [ENQUEUE_ON_FAILURE] and [DISCARD_ON_FAILURE] are opposite overrides of the same header —
 * regression coverage for exactly the kind of copy-paste that once left them with the same value. */
class RetryJournalHeadersTest {

    @Test
    fun `enqueue and discard constants carry opposite values`() {
        assertNotEquals(RetryJournalHeaders.ENQUEUE_ON_FAILURE, RetryJournalHeaders.DISCARD_ON_FAILURE)
        assertEquals("${RetryJournalHeaders.ENQUEUE_OVERRIDE}: true", RetryJournalHeaders.ENQUEUE_ON_FAILURE)
        assertEquals("${RetryJournalHeaders.ENQUEUE_OVERRIDE}: false", RetryJournalHeaders.DISCARD_ON_FAILURE)
    }
}
