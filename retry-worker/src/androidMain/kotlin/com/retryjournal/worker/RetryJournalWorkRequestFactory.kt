package com.retryjournal.worker

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import java.util.concurrent.TimeUnit

/** Pure builders — no `Context`/`WorkManager` — so [AndroidRetryJournalWorkerScheduler]'s request
 * shape is unit-testable without an Android runtime. */

internal fun buildRetryJournalConstraints(config: RetryJournalSchedulerConfig): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(
            if (config.requiresNetwork) {
                NetworkType.CONNECTED
            } else {
                NetworkType.NOT_REQUIRED
            },
        )
        .build()

internal fun buildRetryJournalInputData(config: RetryJournalSchedulerConfig): Data =
    workDataOf(RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS to config.maxRetryAttempts)

internal fun buildRetryJournalPeriodicWorkRequest(config: RetryJournalSchedulerConfig): PeriodicWorkRequest =
    PeriodicWorkRequestBuilder<RetryJournalCoroutineWorker>(config.intervalMs, TimeUnit.MILLISECONDS)
        .setConstraints(buildRetryJournalConstraints(config))
        .setInputData(buildRetryJournalInputData(config))
        .setBackoffCriteria(BackoffPolicy.LINEAR, config.retryDelayMs, TimeUnit.MILLISECONDS)
        .build()
