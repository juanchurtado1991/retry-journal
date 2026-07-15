package com.ghostserializer.sync.sample.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

class GhostSyncSampleApplication : Application() {
    companion object {
        private var currentActivityRef = WeakReference<Activity?>(null)
        val currentActivity: Activity? get() = currentActivityRef.get()
    }

    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.install(this)
        setUpKmpWorkManager(this)

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
