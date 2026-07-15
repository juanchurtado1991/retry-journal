package com.retryjournal

import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files

actual fun freshTestDir(prefix: String): Path =
    Files.createTempDirectory(prefix).toString().toPath()
