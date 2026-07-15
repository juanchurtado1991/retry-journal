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
        val runtime = RetryJournalWorkerRegistry.runtimeProvider?.invoke() ?: return Result.failure()
        val maxRetryAttempts = inputData.getInt(
            RetryJournalWorkerConstants.KEY_MAX_RETRY_ATTEMPTS,
            RetryJournalSchedulerConfig().maxRetryAttempts,
        )
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
