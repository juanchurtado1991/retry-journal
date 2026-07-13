@file:OptIn(ExperimentalForeignApi::class)

package com.ghostserializer.sync.queue

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.FileSystem
import okio.Path
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.flock
import platform.posix.open

internal actual class PlatformQueueFileLock actual constructor(
    private val lockPath: Path,
    private val fileSystem: FileSystem,
) {
    private var fd: Int = -1

    actual fun acquire() {
        val parent = lockPath.parent
        if (parent != null) {
            fileSystem.createDirectories(parent, mustCreate = false)
        }
        val path = lockPath.toString()
        fd = memScoped {
            open(path.cstr.ptr, O_CREAT or O_RDWR, S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH)
        }
        if (fd < 0 || flock(fd, LOCK_EX) != 0) {
            if (fd >= 0) {
                close(fd)
            }
            fd = -1
            error(DiskQueueConstants.LOCK_ACQUIRE_FAILED_MESSAGE)
        }
    }

    actual fun release() {
        if (fd < 0) {
            return
        }
        flock(fd, LOCK_UN)
        close(fd)
        fd = -1
    }
}
