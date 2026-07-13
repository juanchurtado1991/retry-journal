package com.ghostserializer.sync.queue

import okio.FileSystem
import okio.Path

/** Advisory exclusive lock for a queue file — serializes access across processes that share the
 * same [queuePath]. Intra-process concurrency is still handled by [DiskQueue]'s [Mutex]. */
internal expect class PlatformQueueFileLock(lockPath: Path, fileSystem: FileSystem) {
    fun acquire()
    fun release()
}
