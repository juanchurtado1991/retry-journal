package com.retryjournal.worker

import com.retryjournal.engine.FlushResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetryJournalWorkOutcomeTest {

    // --- isRetryJournalFlushSuccessful ---

    @Test
    fun testFlushSuccessfulIsFalseWhenOffline() {
        assertFalse(isRetryJournalFlushSuccessful(null))
    }

    @Test
    fun testFlushSuccessfulIsTrueWhenQueueFullyDrained() {
        assertTrue(
            isRetryJournalFlushSuccessful(
                FlushResult(delivered = 3, deadLettered = 1, stoppedEarly = false),
            ),
        )
    }

    @Test
    fun testFlushSuccessfulIsTrueEvenWithZeroDelivered() {
        // Empty queue: nothing to deliver, but the flush still completed (stoppedEarly = false).
        assertTrue(
            isRetryJournalFlushSuccessful(
                FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = false),
            ),
        )
    }

    @Test
    fun testFlushSuccessfulIsFalseWhenStoppedEarly() {
        assertFalse(
            isRetryJournalFlushSuccessful(
                FlushResult(delivered = 5, deadLettered = 0, stoppedEarly = true),
            ),
        )
    }

    @Test
    fun testFlushSuccessfulIgnoresPersistenceFailedFlag() {
        // persistenceFailed only affects local queue bookkeeping (a DeliveryJournal was written) —
        // it must not change whether the background run itself is reported successful.
        assertTrue(
            isRetryJournalFlushSuccessful(
                FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false, persistenceFailed = true),
            ),
        )
        assertFalse(
            isRetryJournalFlushSuccessful(
                FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = true, persistenceFailed = true),
            ),
        )
    }

    // --- shouldRetryWork ---

    @Test
    fun testShouldRetryOnFirstAttempt() {
        assertTrue(shouldRetryWork(attemptCount = 0, maxRetryAttempts = 5))
    }

    @Test
    fun testShouldRetryOnLastAllowedAttempt() {
        assertTrue(shouldRetryWork(attemptCount = 4, maxRetryAttempts = 5))
    }

    @Test
    fun testShouldNotRetryOnceCapReached() {
        assertFalse(shouldRetryWork(attemptCount = 5, maxRetryAttempts = 5))
    }

    @Test
    fun testShouldNotRetryPastCap() {
        assertFalse(shouldRetryWork(attemptCount = 100, maxRetryAttempts = 5))
    }

    @Test
    fun testShouldNotRetryWhenMaxRetryAttemptsIsZero() {
        assertFalse(shouldRetryWork(attemptCount = 0, maxRetryAttempts = 0))
    }

    @Test
    fun testShouldRetryWhenMaxRetryAttemptsIsLarge() {
        assertTrue(shouldRetryWork(attemptCount = 0, maxRetryAttempts = Int.MAX_VALUE))
    }
}
