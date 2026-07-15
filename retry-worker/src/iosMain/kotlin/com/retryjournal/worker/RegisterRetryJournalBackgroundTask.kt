@file:OptIn(ExperimentalForeignApi::class)

package com.retryjournal.worker

import com.retryjournal.RetryJournalRuntime
import com.retryjournal.scheduler.RetryJournalScheduler
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGTaskScheduler

/**
 * Registers the `:retry-worker` iOS background task launch handler and returns a
 * [RetryJournalScheduler] to (re)schedule or cancel it. Per Apple's rule, task registration must
 * happen synchronously before `application(_:didFinishLaunchingWithOptions:)` returns — call
 * this from Swift as early as possible (e.g. `AppDelegate.init()`), and make sure
 * [taskIdentifier] is also listed under `BGTaskSchedulerPermittedIdentifiers` in Info.plist.
 */
fun registerRetryJournalBackgroundTask(
    taskIdentifier: String,
    runtime: RetryJournalRuntime,
    config: RetryJournalSchedulerConfig = RetryJournalSchedulerConfig(),
): RetryJournalScheduler {
    val scheduler = IosRetryJournalWorkerScheduler(taskIdentifier)
    val taskScope = CoroutineScope(Dispatchers.Default)

    BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
        identifier = taskIdentifier,
        usingQueue = null,
    ) { task ->
        handleBackgroundTask(task as BGAppRefreshTask, runtime, scheduler, taskScope)
    }

    scheduler.schedule(config)
    return scheduler
}

private fun handleBackgroundTask(
    task: BGAppRefreshTask,
    runtime: RetryJournalRuntime,
    scheduler: IosRetryJournalWorkerScheduler,
    taskScope: CoroutineScope,
) {
    // BGTaskScheduler never repeats a request — the next run must be re-submitted as this one starts.
    scheduler.schedule(scheduler.lastConfig)

    val job = taskScope.launch {
        try {
            val flushResult = runtime.flushWhenOnline()
            task.setTaskCompletedWithSuccess(isRetryJournalFlushSuccessful(flushResult))
        } catch (_: CancellationException) {
            // expirationHandler below already completed the task.
        } catch (_: Throwable) {
            task.setTaskCompletedWithSuccess(false)
        }
    }

    task.expirationHandler = {
        job.cancel()
        task.setTaskCompletedWithSuccess(false)
    }
}
