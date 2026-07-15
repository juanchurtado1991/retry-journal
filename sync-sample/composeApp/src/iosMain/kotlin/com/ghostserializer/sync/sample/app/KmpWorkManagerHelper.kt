package com.ghostserializer.sync.sample.app

import dev.brewkits.kmpworkmanager.KmpWorkManagerConfig
import dev.brewkits.kmpworkmanager.background.data.IosBackgroundTaskHandler
import dev.brewkits.kmpworkmanager.kmpWorkerModule
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatformTools
import platform.BackgroundTasks.BGTask
import dev.brewkits.kmpworkmanager.background.data.ChainExecutor
import org.koin.core.KoinApplication

private const val BACKGROUND_TASK_ID = "ghost_sync_task"

object KmpWorkManagerHelper {
    private var koinApp: KoinApplication? = null

    fun initialize() {
        koinApp = startKoin {
            modules(kmpWorkerModule(
                workerFactory = SampleIosWorkerFactory(),
                config = KmpWorkManagerConfig(
                    minFreeDiskSpaceBytes = 0
                ),
                iosTaskIds = setOf(BACKGROUND_TASK_ID)
            ))
        }
    }

    fun handleBackgroundTask(task: BGTask) {
        val koin = koinApp?.koin ?: KoinPlatformTools.defaultContext().get()
        val chainExecutor = koin.get<ChainExecutor>(ChainExecutor::class, null, null)
        IosBackgroundTaskHandler.shared.handleChainExecutorTask(task, chainExecutor)
    }
}
