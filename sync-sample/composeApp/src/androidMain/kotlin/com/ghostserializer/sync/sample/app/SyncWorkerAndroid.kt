package com.ghostserializer.sync.sample.app

import dev.brewkits.kmpworkmanager.annotations.Worker
import dev.brewkits.kmpworkmanager.background.domain.AndroidWorker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/** Thin platform adapter — the actual logic is [GhostSyncWorker], written once in commonMain. */
@Worker(name = AppConstants.SYNC_WORKER_NAME)
class SyncWorkerAndroid : AndroidWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
        GhostSyncWorker().doWork(input, env)
}
