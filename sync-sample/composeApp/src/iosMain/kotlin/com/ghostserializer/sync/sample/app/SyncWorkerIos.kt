package com.ghostserializer.sync.sample.app

import dev.brewkits.kmpworkmanager.annotations.Worker
import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.domain.WorkerEnvironment
import dev.brewkits.kmpworkmanager.background.domain.WorkerResult

/**
 * Thin platform adapter — the actual logic is [GhostSyncWorker], written once in commonMain.
 * `bgTaskId` must match the identifier registered with `BGTaskScheduler` in iosApp's AppDelegate
 * and listed under `BGTaskSchedulerPermittedIdentifiers` in Info.plist — see iosApp/README.md.
 */
@Worker(name = AppConstants.SYNC_WORKER_NAME, bgTaskId = AppConstants.IOS_BACKGROUND_TASK_ID)
class SyncWorkerIos : IosWorker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult =
        GhostSyncWorker().doWork(input, env)
}
