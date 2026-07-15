@file:OptIn(ExperimentalForeignApi::class)

package com.retryjournal.worker

import com.retryjournal.scheduler.RetryJournalScheduler
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval

/**
 * [RetryJournalScheduler] backed by `BGTaskScheduler` — the iOS half of `:retry-worker`.
 * Obtained from [registerRetryJournalBackgroundTask], never constructed directly — `BGAppRefreshTask`
 * needs its launch handler registered before any request can be submitted.
 */
class IosRetryJournalWorkerScheduler internal constructor(
    private val taskIdentifier: String,
) : RetryJournalScheduler {

    @Volatile
    internal var lastConfig: RetryJournalSchedulerConfig = RetryJournalSchedulerConfig()
        private set

    override fun schedule(config: RetryJournalSchedulerConfig) {
        lastConfig = config
        val request = BGAppRefreshTaskRequest(identifier = taskIdentifier)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(
            config.intervalMs / RetryJournalIosWorkerConstants.MILLIS_PER_SECOND,
        )
        // Best-effort — BGTaskScheduler rejects submissions when the app hasn't run in the
        // background recently or the identifier isn't declared; the next foreground launch
        // (registerRetryJournalBackgroundTask re-schedules on every app start) retries.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }

    override fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
    }
}
