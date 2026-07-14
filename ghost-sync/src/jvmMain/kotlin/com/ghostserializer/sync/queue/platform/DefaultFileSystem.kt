package com.ghostserializer.sync.queue.platform

import okio.FileSystem

internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
