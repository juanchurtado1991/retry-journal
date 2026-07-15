package com.retryjournal.scheduler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RetryJournalSchedulerConfigTest {

    @Test
    fun testDefaultsMatchDocumentedValues() {
        val config = RetryJournalSchedulerConfig()

        assertEquals(15 * 60 * 1_000L, config.intervalMs)
        assertTrue(config.requiresNetwork)
        assertEquals(60_000L, config.retryDelayMs)
        assertEquals(5, config.maxRetryAttempts)
    }

    @Test
    fun testCustomValuesOverrideOnlyWhatIsPassed() {
        val config = RetryJournalSchedulerConfig(intervalMs = 5_000L)

        assertEquals(5_000L, config.intervalMs)
        // Everything else keeps the default — a partial override must not reset the rest.
        assertTrue(config.requiresNetwork)
        assertEquals(60_000L, config.retryDelayMs)
        assertEquals(5, config.maxRetryAttempts)
    }

    @Test
    fun testAllFieldsOverridable() {
        val config = RetryJournalSchedulerConfig(
            intervalMs = 1_000L,
            requiresNetwork = false,
            retryDelayMs = 2_000L,
            maxRetryAttempts = 1,
        )

        assertEquals(1_000L, config.intervalMs)
        assertEquals(false, config.requiresNetwork)
        assertEquals(2_000L, config.retryDelayMs)
        assertEquals(1, config.maxRetryAttempts)
    }

    @Test
    fun testEqualsAndHashCodeAreStructural() {
        val a = RetryJournalSchedulerConfig(intervalMs = 1_000L, maxRetryAttempts = 2)
        val b = RetryJournalSchedulerConfig(intervalMs = 1_000L, maxRetryAttempts = 2)
        val c = RetryJournalSchedulerConfig(intervalMs = 1_000L, maxRetryAttempts = 3)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun testCopyOverridesOnlyTheGivenField() {
        val original = RetryJournalSchedulerConfig(
            intervalMs = 1_000L,
            requiresNetwork = true,
            retryDelayMs = 2_000L,
            maxRetryAttempts = 3,
        )

        val copy = original.copy(requiresNetwork = false)

        assertEquals(original.intervalMs, copy.intervalMs)
        assertEquals(false, copy.requiresNetwork)
        assertEquals(original.retryDelayMs, copy.retryDelayMs)
        assertEquals(original.maxRetryAttempts, copy.maxRetryAttempts)
    }
}
