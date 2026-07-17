package com.retryjournal.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import kotlinx.coroutines.CancellationException

/**
 * WorkManager's periodic worker for `:retry-worker`. Instantiated by reflection by
 * WorkManager's default `WorkerFactory` — the [RetryJournalRuntime][com.retryjournal.RetryJournalRuntime]
 * it drives comes from [RetryJournalWorkerRegistry], not a constructor argument.
 */
class RetryJournalCoroutineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val maxRetryAttempts = inputData.getInt(
            RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS,
            RetryJournalSchedulerConfig().maxRetryAttempts,
        )
        // A null provider here means WorkManager instantiated this worker (by reflection, on its
        // own schedule) before setupBackgroundSync() ran and registered one — e.g. the app builds
        // RetryJournalRuntime asynchronously and this periodic run landed in that window. That's a
        // transient condition, not a permanent one, so it goes through the same short backoff
        // budget as any other flush failure instead of unconditionally failing this run and waiting
        // a full intervalMs for the next scheduled attempt.
        val runtime = RetryJournalWorkerRegistry.runtimeProvider?.invoke()
            ?: return retryOrFail(maxRetryAttempts)
        return try {
            val flushResult = runtime.flushWhenOnline()
            if (isRetryJournalFlushSuccessful(flushResult)) {
                Result.success()
            } else {
                retryOrFail(maxRetryAttempts)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            retryOrFail(maxRetryAttempts)
        }
    }

    private fun retryOrFail(maxRetryAttempts: Int): Result {
        return if (shouldRetryWork(runAttemptCount, maxRetryAttempts)) {
            Result.retry()
        } else {
            Result.failure()
        }
    }
}
