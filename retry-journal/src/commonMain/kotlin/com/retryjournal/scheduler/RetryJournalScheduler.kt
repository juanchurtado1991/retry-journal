package com.retryjournal.scheduler

/**
 * Platform background-dispatch contract. `:retry-journal` never implements this — it only defines
 * the shape so any scheduling infrastructure (WorkManager, BGTaskScheduler, a custom cron, or
 * `:retry-worker`'s out-of-the-box implementations) can drive [com.retryjournal.RetryJournalRuntime.flushWhenOnline]
 * on a timer without `:retry-journal` depending on that infrastructure.
 */
interface RetryJournalScheduler {
    /** Schedules (or re-schedules, replacing any prior request) periodic background sync. */
    fun schedule(config: RetryJournalSchedulerConfig)

    /** Cancels any pending periodic background sync scheduled via [schedule]. */
    fun cancel()
}
