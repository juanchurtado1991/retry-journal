package com.retryjournal.queue

import com.retryjournal.queue.platform.PlatformQueueFileLock
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Entry point for [DiskQueueCrossProcessLockTest] — launched as a genuinely separate `java`
 * process so that test exercises real OS-level file locking (`FileChannel.lock()` contended
 * across process boundaries) instead of [PlatformQueueFileLock]'s intra-JVM `ReentrantLock` layer,
 * which every other lock test in this module (including two `DiskQueue` instances in the same
 * process) actually goes through instead.
 *
 * Protocol, coordinated via marker files rather than stdio pipes (simpler to poll from both a
 * `ProcessBuilder`-launched child and a coroutine on the parent side):
 * 1. Acquire the lock at `args[0]`.
 * 2. Create `args[1]` (the "ready" marker) to signal the parent that the OS-level lock is held.
 * 3. Poll for `args[2]` (the "release" marker) to appear.
 * 4. Release the lock and exit.
 */
fun main(args: Array<String>) {
    val lockPath = args[0].toPath()
    val readyMarkerPath = args[1].toPath()
    val releaseMarkerPath = args[2].toPath()

    val lock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
    lock.acquire()
    FileSystem.SYSTEM.write(readyMarkerPath) { writeUtf8("ready") }

    while (!FileSystem.SYSTEM.exists(releaseMarkerPath)) {
        Thread.sleep(20)
    }

    lock.release()
}
