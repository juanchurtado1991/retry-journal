package com.retryjournal.worker

import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs on the real BackgroundTasks framework via `iosSimulatorArm64Test` — `submitTaskRequest`
 * is best-effort (see [IosRetryJournalWorkerScheduler.schedule]) so it never throws even when the
 * test target has no `BGTaskSchedulerPermittedIdentifiers` entry for [taskIdentifier]; these
 * tests only assert the state [IosRetryJournalWorkerScheduler] itself owns. */
class IosRetryJournalWorkerSchedulerTest {
    private val taskIdentifier = "com.retryjournal.worker.test.task"

    @Test
    fun testLastConfigDefaultsBeforeAnySchedule() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        assertEquals(RetryJournalSchedulerConfig(), scheduler.lastConfig)
    }

    @Test
    fun testScheduleUpdatesLastConfig() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        val config = RetryJournalSchedulerConfig(
            intervalMs = 30 * 60 * 1_000L,
            requiresNetwork = false,
            retryDelayMs = 5_000L,
            maxRetryAttempts = 2,
        )

        scheduler.schedule(config)

        assertEquals(config, scheduler.lastConfig)
    }

    @Test
    fun testRepeatedScheduleKeepsMostRecentConfig() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)

        scheduler.schedule(RetryJournalSchedulerConfig(intervalMs = 20 * 60 * 1_000L))
        scheduler.schedule(RetryJournalSchedulerConfig(intervalMs = 45 * 60 * 1_000L))

        assertEquals(45 * 60 * 1_000L, scheduler.lastConfig.intervalMs)
    }

    @Test
    fun testCancelDoesNotThrowBeforeAnySchedule() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.cancel()
    }

    @Test
    fun testCancelDoesNotThrowAfterSchedule() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig())
        scheduler.cancel()
    }

    // --- consecutive-failure backoff (rescheduleAfterRun) ---

    @Test
    fun testConsecutiveFailuresStartsAtZero() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        assertEquals(0, scheduler.consecutiveFailures)
    }

    @Test
    fun testFailedRunIncrementsConsecutiveFailures() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig(maxRetryAttempts = 5))

        scheduler.rescheduleAfterRun(success = false)
        assertEquals(1, scheduler.consecutiveFailures)

        scheduler.rescheduleAfterRun(success = false)
        assertEquals(2, scheduler.consecutiveFailures)
    }

    @Test
    fun testSuccessfulRunResetsConsecutiveFailures() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig(maxRetryAttempts = 5))

        scheduler.rescheduleAfterRun(success = false)
        scheduler.rescheduleAfterRun(success = false)
        assertTrue(scheduler.consecutiveFailures > 0)

        scheduler.rescheduleAfterRun(success = true)
        assertEquals(0, scheduler.consecutiveFailures)
    }

    @Test
    fun testFreshScheduleResetsConsecutiveFailures() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig(maxRetryAttempts = 5))
        scheduler.rescheduleAfterRun(success = false)
        assertTrue(scheduler.consecutiveFailures > 0)

        scheduler.schedule(RetryJournalSchedulerConfig(maxRetryAttempts = 5))

        assertEquals(0, scheduler.consecutiveFailures)
    }

    @Test
    fun testConsecutiveFailuresKeepsCountingPastMaxRetryAttempts() {
        // The scheduler doesn't cap the counter itself — rescheduleAfterRun just stops using the
        // short retryDelayMs interval once past maxRetryAttempts; a later success still resets it.
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig(maxRetryAttempts = 2))

        repeat(5) { scheduler.rescheduleAfterRun(success = false) }

        assertEquals(5, scheduler.consecutiveFailures)
    }

    @Test
    fun testRearmBackstopDoesNotThrowOrTouchFailureStreak() {
        val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
        scheduler.schedule(RetryJournalSchedulerConfig())
        scheduler.rescheduleAfterRun(success = false)
        val before = scheduler.consecutiveFailures

        scheduler.rearmBackstop()

        assertEquals(before, scheduler.consecutiveFailures)
    }
}
