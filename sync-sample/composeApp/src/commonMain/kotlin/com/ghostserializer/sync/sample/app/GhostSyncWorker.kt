package com.ghostserializer.sync.sample.app

import com.ghostserializer.sync.engine.GhostSyncEngine
import dev.brewkits.kmpworkmanager.background.domain.Worker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/**
 * The actual sync logic, written once in commonMain — kmpworkmanager's pattern has thin platform
 * adapters (`AndroidWorker`/`IosWorker` implementations annotated `@Worker`) delegate to a plain,
 * testable class like this one. This class lives in the sample, never in `:ghost-sync` — see
 * CONVENTIONS.md.
 *
 * `Worker`/`WorkerResult`/`WorkerEnvironment` live in `dev.brewkits.kmpworkmanager.background.domain`
 * — confirmed by decompiling the resolved `kmpworkmanager-android` artifact (the library's own
 * README omits the package on these, and its `WorkerResult.Retry(delayMs, attemptCap)` snippet
 * omits a third, non-optional `reason: String` constructor parameter that the real class
 * requires). The iOS side wasn't independently verified on this machine (Linux, no Xcode), but
 * KMP libraries keep a shared interface in one commonMain package, so the same package for
 * `IosWorker` is a safe bet.
 */
class GhostSyncWorker(
    private val engine: GhostSyncEngine = SyncSetup.syncEngine,
) : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        return try {
            val result = engine.flush(SyncSetup.replayClient)
            if (result.stoppedEarly) {
                retry(reason = "flush stopped early: a 5xx or network failure left work in the queue")
            } else {
                WorkerResult.Success("delivered=${result.delivered} deadLettered=${result.deadLettered}")
            }
        } catch (cause: Exception) {
            retry(reason = "flush threw: ${cause.message}")
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
