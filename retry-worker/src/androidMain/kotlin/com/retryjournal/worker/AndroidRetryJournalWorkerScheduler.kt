package com.retryjournal.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.retryjournal.scheduler.RetryJournalScheduler
import com.retryjournal.scheduler.RetryJournalSchedulerConfig

/** [RetryJournalScheduler] backed by Jetpack WorkManager — the Android half of `:retry-worker`. */
class AndroidRetryJournalWorkerScheduler(
    private val context: Context,
) : RetryJournalScheduler {

    override fun schedule(config: RetryJournalSchedulerConfig) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RetryJournalWorkerConstants.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRetryJournalPeriodicWorkRequest(config),
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(RetryJournalWorkerConstants.UNIQUE_WORK_NAME)
    }
}
