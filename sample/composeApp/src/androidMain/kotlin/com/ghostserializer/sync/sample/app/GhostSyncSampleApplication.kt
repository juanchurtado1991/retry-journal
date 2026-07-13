package com.ghostserializer.sync.sample.app

import android.app.Application

class GhostSyncSampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.install(this)
        setUpKmpWorkManager(this)
    }
}
