package com.retryjournal.worker

import android.content.Context
import com.retryjournal.RetryJournalRuntime
import com.retryjournal.scheduler.RetryJournalScheduler
import com.retryjournal.scheduler.RetryJournalSchedulerConfig

/**
 * Out-of-the-box background sync: registers [this] runtime with [RetryJournalCoroutineWorker] and
 * schedules it via WorkManager. Call once at process start (e.g. `Application.onCreate`), after
 * building [RetryJournalRuntime].
 */
fun RetryJournalRuntime.setupBackgroundSync(
    context: Context,
    config: RetryJournalSchedulerConfig = RetryJournalSchedulerConfig(),
): RetryJournalScheduler {
    RetryJournalWorkerRegistry.runtimeProvider = { this }
    val scheduler = AndroidRetryJournalWorkerScheduler(context.applicationContext)
    scheduler.schedule(config)
    return scheduler
}
