package com.ghostserializer.sync.queue.platform

import okio.FileSystem

internal expect fun systemFileSystem(): FileSystem
