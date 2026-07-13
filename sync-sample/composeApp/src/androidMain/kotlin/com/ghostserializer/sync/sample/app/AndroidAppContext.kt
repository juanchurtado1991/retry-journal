package com.ghostserializer.sync.sample.app

import android.app.Application

internal object AndroidAppContext {
    private var application: Application? = null

    fun install(app: Application) {
        application = app
    }

    fun requireApplication(): Application =
        checkNotNull(application) { AppStrings.ANDROID_APP_CONTEXT_NOT_INSTALLED }
}
