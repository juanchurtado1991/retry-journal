package com.ghostserializer.sync.sample.app

import dev.brewkits.kmpworkmanager.background.data.IosWorker
import dev.brewkits.kmpworkmanager.background.data.IosWorkerFactory
import dev.brewkits.kmpworkmanager.background.domain.BgTaskIdProvider

private const val BACKGROUND_TASK_ID = "ghost_sync_task"
private const val GHOST_SYNC_WORKER_CLASS = "GhostSyncWorker"
private const val SYNC_WORKER_IOS_CLASS = "com.ghostserializer.sync.sample.app.SyncWorkerIos"

class SampleIosWorkerFactory : IosWorkerFactory, BgTaskIdProvider {
    override val requiredBgTaskIds: Set<String> = setOf(BACKGROUND_TASK_ID)

    override fun createWorker(workerClassName: String): IosWorker? {
        return if (
            workerClassName == GHOST_SYNC_WORKER_CLASS ||
            workerClassName == SYNC_WORKER_IOS_CLASS
        ) {
            SyncWorkerIos()
        } else {
            null
        }
    }
}
