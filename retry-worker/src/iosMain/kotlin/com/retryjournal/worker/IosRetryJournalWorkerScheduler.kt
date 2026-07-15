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

    /** Consecutive failed/incomplete runs since the last success — drives [rescheduleAfterRun]'s
     * backoff. Resets to 0 on success or on a fresh [schedule] call. Lives only in memory, so a
     * full process restart between background launches (common on iOS) loses the streak — the
     * next run simply falls back to [RetryJournalSchedulerConfig.intervalMs], which is safe. */
    @Volatile
    internal var consecutiveFailures: Int = 0
        private set

    override fun schedule(config: RetryJournalSchedulerConfig) {
        lastConfig = config
        consecutiveFailures = 0
        submitRequest(config.intervalMs)
    }

    override fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(taskIdentifier)
    }

    /** Re-arms the next background run at [lastConfig]'s normal interval — a backstop so a future
     * wakeup is guaranteed even if the current run gets expired before [rescheduleAfterRun] can
     * run. Does not touch the consecutive-failure streak. */
    internal fun rearmBackstop() {
        submitRequest(lastConfig.intervalMs)
    }

    /**
     * Called once a background run finishes (or expires) — reschedules sooner, at
     * [RetryJournalSchedulerConfig.retryDelayMs], for up to
     * [RetryJournalSchedulerConfig.maxRetryAttempts] consecutive failed/incomplete runs, then
     * falls back to the normal [RetryJournalSchedulerConfig.intervalMs] — the same backoff shape
     * WorkManager gives Android. `earliestBeginDate` is only a hint to iOS, not a guarantee — the
     * OS still decides the actual fire time based on device/battery state.
     */
    internal fun rescheduleAfterRun(success: Boolean) {
        if (success) {
            consecutiveFailures = 0
            submitRequest(lastConfig.intervalMs)
            return
        }
        consecutiveFailures += 1
        val intervalMs = if (consecutiveFailures <= lastConfig.maxRetryAttempts) {
            lastConfig.retryDelayMs
        } else {
            lastConfig.intervalMs
        }
        submitRequest(intervalMs)
    }

    private fun submitRequest(intervalMs: Long) {
        // Replace any pending request for this identifier outright — BGTaskScheduler allows only
        // one at a time, and cancel() is a safe no-op when nothing is pending.
        cancel()
        val request = BGAppRefreshTaskRequest(identifier = taskIdentifier)
        request.earliestBeginDate = NSDate().dateByAddingTimeInterval(
            intervalMs / RetryJournalIosWorkerConstants.MILLIS_PER_SECOND,
        )
        // Best-effort — BGTaskScheduler rejects submissions when the app hasn't run in the
        // background recently or the identifier isn't declared; the next foreground launch
        // (registerRetryJournalBackgroundTask re-schedules on every app start) retries.
        BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
    }
}
