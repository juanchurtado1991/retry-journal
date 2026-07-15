@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.retryjournal.queue.platform

import okio.FileSystem
import okio.Path

/** Advisory exclusive lock for a queue file — serializes access across processes that share the
 * same `queuePath`. Intra-process concurrency is still handled by
 * [DiskQueue][com.retryjournal.queue.disk.DiskQueue]'s own `Mutex`. */
internal expect class PlatformQueueFileLock(lockPath: Path, fileSystem: FileSystem) {
    fun acquire()
    fun release()
}
