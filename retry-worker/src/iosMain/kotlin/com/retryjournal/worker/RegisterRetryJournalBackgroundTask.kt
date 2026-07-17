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
    // BGTaskScheduler never repeats a request — a future run must be re-armed as this one starts,
    // as a backstop in case this run gets expired before rescheduleAfterRun() below can replace it.
    scheduler.rearmBackstop()

    // Coroutine cancellation is cooperative: job.cancel() below doesn't interrupt anything already
    // past its last suspension point, so the job's own try body and expirationHandler can both
    // reach their "reschedule + setTaskCompletedWithSuccess" tail at once (the job finishes right
    // as the system decides to expire it) — without a guard, both fire, double-completing the
    // same BGTask and letting a later, wrong reschedule stomp the correct one. RunOnce ensures
    // exactly one of them ever runs that tail.
    val runOnce = RunOnce()
    fun finishOnce(success: Boolean) = runOnce.runOnce {
        scheduler.rescheduleAfterRun(success)
        task.setTaskCompletedWithSuccess(success)
    }

    val job = taskScope.launch {
        try {
            val flushResult = runtime.flushWhenOnline()
            finishOnce(isRetryJournalFlushSuccessful(flushResult))
        } catch (_: CancellationException) {
            // Either expirationHandler below already finished this task, or cancellation came from
            // somewhere else entirely (e.g. an unrelated withTimeout upstream) — finishOnce()
            // covers both: a no-op if already finished, or a safe "failed" completion otherwise,
            // so the BGTask is never left uncompleted.
            finishOnce(false)
        } catch (_: Throwable) {
            finishOnce(false)
        }
    }

    task.expirationHandler = {
        job.cancel()
        finishOnce(false)
    }
}
