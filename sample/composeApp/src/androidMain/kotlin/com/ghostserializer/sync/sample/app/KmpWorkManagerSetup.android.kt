package com.ghostserializer.sync.sample.app

import android.app.Application
import dev.brewkits.kmpworkmanager.KmpWorkManager
import dev.brewkits.kmpworkmanager.background.domain.Constraints
import dev.brewkits.kmpworkmanager.background.domain.TaskTrigger
import dev.brewkits.kmpworkmanager.generated.AndroidWorkerFactoryGenerated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Registers [SyncWorkerAndroid] with kmpworkmanager and schedules the periodic sync — the
 * reference integration from CONVENTIONS.md's "any scheduler" design principle. `:ghost-sync`
 * itself never imports this library; only this sample app does.
 *
 * Every symbol here was confirmed by actually running `:sample:composeApp:kspDebugKotlinAndroid`
 * against the resolved `dev.brewkits:kmpworkmanager-android:3.0.1` artifact and reading its
 * generated output on this machine — not just the library's README, which omits the exact
 * package and the scheduler accessor. `AndroidWorkerFactoryGenerated` (package
 * `dev.brewkits.kmpworkmanager.generated`) is the real class KSP emits from the
 * `@Worker`-annotated [SyncWorkerAndroid]; `KmpWorkManager.getInstance().backgroundTaskScheduler`
 * is the real scheduler accessor (decompiled from the artifact's bytecode).
 */
internal fun setUpKmpWorkManager(application: Application) {
    KmpWorkManager.initialize(
        context = application,
        workerFactory = AndroidWorkerFactoryGenerated(),
    )

    CoroutineScope(Dispatchers.Default).launch {
        KmpWorkManager.getInstance().backgroundTaskScheduler.enqueue(
            id = AppConstants.SYNC_WORKER_ID,
            trigger = TaskTrigger.Periodic(intervalMs = AppConstants.SYNC_INTERVAL_MS),
            workerClassName = AppConstants.SYNC_WORKER_NAME,
            constraints = Constraints(requiresNetwork = true),
        )
    }
}
