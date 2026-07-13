package com.ghostserializer.sync.queue

import okio.FileSystem
import okio.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.nio.file.StandardOpenOption

internal actual class PlatformQueueFileLock actual constructor(
    private val lockPath: Path,
    private val fileSystem: FileSystem,
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    actual fun acquire() {
        val parent = lockPath.parent
        if (parent != null) {
            fileSystem.createDirectories(parent, mustCreate = false)
        }
        val nioPath: NioPath = java.nio.file.Paths.get(lockPath.toString())
        Files.createDirectories(nioPath.parent ?: nioPath)
        channel = FileChannel.open(
            nioPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        lock = channel!!.lock()
    }

    actual fun release() {
        try {
            lock?.release()
        } finally {
            lock = null
            channel?.close()
            channel = null
        }
    }
}
