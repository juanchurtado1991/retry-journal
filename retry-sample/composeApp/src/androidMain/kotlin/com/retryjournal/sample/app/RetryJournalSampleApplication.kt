package com.retryjournal.sample.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import com.retryjournal.worker.setupBackgroundSync
import java.lang.ref.WeakReference

class RetryJournalSampleApplication : Application() {
    companion object {
        private var currentActivityRef = WeakReference<Activity?>(null)
        val currentActivity: Activity? get() = currentActivityRef.get()
    }

    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.install(this)
        SyncSetup.runtime.setupBackgroundSync(
            context = this,
            config = RetryJournalSchedulerConfig(
                intervalMs = AppConstants.SYNC_INTERVAL_MS,
                retryDelayMs = AppConstants.SYNC_RETRY_DELAY_MS,
                maxRetryAttempts = AppConstants.SYNC_MAX_RETRY_ATTEMPTS,
            ),
        )

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef = WeakReference(activity)
            }
            override fun onActivityStarted(activity: Activity) {
                currentActivityRef = WeakReference(activity)
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef.get() == activity) {
                    currentActivityRef.clear()
                }
            }
        })
    }
}
