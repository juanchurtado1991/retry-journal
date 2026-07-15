package com.retryjournal.queue.platform

import okio.FileSystem

internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
