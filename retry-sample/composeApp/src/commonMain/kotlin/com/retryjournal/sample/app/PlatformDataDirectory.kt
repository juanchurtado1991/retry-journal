package com.retryjournal.sample.app

/** A writable, app-private directory: `filesDir` on Android, the Documents directory on iOS. */
internal expect fun platformDataDirectory(): String
