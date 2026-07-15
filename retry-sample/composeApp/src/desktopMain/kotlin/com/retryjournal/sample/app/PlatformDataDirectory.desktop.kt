package com.retryjournal.sample.app

import okio.FileSystem
import okio.Path.Companion.toPath

private const val USER_HOME_SYSTEM_PROPERTY: String = "user.home"

internal actual fun platformDataDirectory(): String {
    val directory = (System.getProperty(USER_HOME_SYSTEM_PROPERTY) + "/" + AppConstants.DESKTOP_DATA_DIRECTORY_NAME).toPath()
    FileSystem.SYSTEM.createDirectories(directory, mustCreate = false)
    return directory.toString()
}
