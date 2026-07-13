package com.ghostserializer.sync.sample.app

internal actual fun platformDataDirectory(): String =
    AndroidAppContext.requireApplication().filesDir.absolutePath
