package com.ghostserializer.sync.sample.app

import com.ghostserializer.sync.GhostSyncRuntime
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult
import kotlinx.coroutines.CancellationException

/**
 * Background sync worker — delegates to [GhostSyncRuntime] from `:ghost-sync` so flush is
 * serialized with the UI and respects the same lifecycle coordinator. Platform adapters
 * (SyncWorkerAndroid, SyncWorkerIos) are thin kmpworkmanager shells.
 */
class GhostSyncWorker(
    private val runtime: GhostSyncRuntime = SyncSetup.runtime,
) : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        return try {
            val result = runtime
                .flushWhenOnline()
                ?: return retry(AppStrings.WORKER_RETRY_REASON_OFFLINE)

            if (result.stoppedEarly) {
                retry(reason = AppStrings.WORKER_RETRY_REASON_STOPPED_EARLY)
            } else {
                WorkerResult.Success(
                    AppStrings.WORKER_SUCCESS_MESSAGE_PREFIX + result.delivered +
                        AppStrings.WORKER_SUCCESS_MESSAGE_DEAD_LETTERED + result.deadLettered,
                )
            }
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            retry(reason = AppStrings.WORKER_RETRY_REASON_THREW_PREFIX + cause.message)
        }
    }

    private fun retry(reason: String): WorkerResult.Retry {
        return WorkerResult.Retry(
            reason = reason,
            delayMs = AppConstants.SYNC_RETRY_DELAY_MS,
            attemptCap = AppConstants.SYNC_MAX_RETRY_ATTEMPTS,
        )
    }
}
