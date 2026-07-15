package com.retryjournal.sample.app

import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import com.retryjournal.worker.registerRetryJournalBackgroundTask

/**
 * Swift-facing surface for `:retry-worker`'s iOS scheduler — kept as a plain no-arg function
 * so the exported Objective-C header stays trivial for `AppDelegate.swift` to call. Per Apple's
 * rule, [register] must run synchronously before `application(_:didFinishLaunchingWithOptions:)`
 * returns, so call it from `AppDelegate.init()`.
 */
object RetryJournalBackgroundSetup {
    fun register() {
        registerRetryJournalBackgroundTask(
            taskIdentifier = AppConstants.IOS_BACKGROUND_TASK_ID,
            runtime = SyncSetup.runtime,
            config = RetryJournalSchedulerConfig(
                intervalMs = AppConstants.SYNC_INTERVAL_MS,
                retryDelayMs = AppConstants.SYNC_RETRY_DELAY_MS,
                maxRetryAttempts = AppConstants.SYNC_MAX_RETRY_ATTEMPTS,
            ),
        )
    }
}
