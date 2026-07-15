package com.retryjournal.worker

import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
