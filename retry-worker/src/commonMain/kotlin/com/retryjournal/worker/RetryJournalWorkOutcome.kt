package com.retryjournal.worker

import com.retryjournal.engine.FlushResult

/**
 * Whether a background run should be reported as successful — shared by the Android worker and
 * the iOS task handler so both platforms make the same call from the same [FlushResult].
 * `null` (offline, [com.retryjournal.RetryJournalRuntime.flushWhenOnline] skipped the flush)
 * and [FlushResult.stoppedEarly] (a 5xx or network failure interrupted replay) are both failures —
 * the caller should retry, not report done.
 */
internal fun isRetryJournalFlushSuccessful(flushResult: FlushResult?): Boolean {
    return flushResult != null && !flushResult.stoppedEarly
}

/**
 * Whether a background run should retry given how many attempts have already happened.
 * [attemptCount] is 0-indexed (the first run's attempt count is `0`), matching
 * `androidx.work.ListenableWorker.runAttemptCount`.
 */
internal fun shouldRetryWork(attemptCount: Int, maxRetryAttempts: Int): Boolean {
    return attemptCount < maxRetryAttempts
}
