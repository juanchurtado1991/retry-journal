package com.retryjournal.sample.app

internal actual fun platformDataDirectory(): String =
    AndroidAppContext.requireApplication().filesDir.absolutePath
