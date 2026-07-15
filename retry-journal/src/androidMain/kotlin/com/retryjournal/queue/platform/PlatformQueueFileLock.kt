package com.retryjournal.queue.platform

import okio.FileSystem
import okio.Path
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * `FileChannel.lock()` is documented as JVM-wide, but in practice the JDK enforces that with its
 * own bookkeeping rather than by blocking: a second [FileChannel] in this same JVM contending for
 * a lock another [FileChannel] here already holds on the same file throws
 * [java.nio.channels.OverlappingFileLockException] immediately instead of waiting. That breaks
 * [DiskQueue][com.retryjournal.queue.disk.DiskQueue]'s documented guarantee that two of its
 * instances can safely share a queue file — two `DiskQueue`s on the same path, used concurrently
 * from real parallel threads in one process (e.g. `Dispatchers.IO`), would crash instead of
 * serializing. [intraJvmLocks] closes that gap by making every acquirer in this JVM queue up on
 * an ordinary blocking lock, keyed by the resolved lock-file path, before ever calling
 * [FileChannel.lock] — so only one thread per JVM is ever attempting that call for a given path
 * at a time, and the OS-level lock is left to do its actual job of arbitrating between separate
 * processes.
 *
 * Uses [File]/[RandomAccessFile] rather than `java.nio.file`'s `Path`-based `FileChannel.open` —
 * that overload (and `Files`/`Paths` generally) requires API 26, and this module's `minSdk` is
 * lower. [File]/[RandomAccessFile]/[FileChannel] (the instance methods, not the `Path`-based
 * static factory) have all been available since API 1. Same reason [jvmLockFor] uses `get` +
 * `putIfAbsent` instead of [ConcurrentHashMap.computeIfAbsent], which requires API 24.
 */
internal actual class PlatformQueueFileLock actual constructor(
    private val lockPath: Path,
    private val fileSystem: FileSystem,
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var heldIntraJvmLock: ReentrantLock? = null

    actual fun acquire() {
        val parent = lockPath.parent
        if (parent != null) {
            fileSystem.createDirectories(parent, mustCreate = false)
        }
        val file = File(lockPath.toString())

        val jvmLock = jvmLockFor(file.absolutePath)
        jvmLock.lock()
        try {
            channel = RandomAccessFile(file, "rw").channel
            lock = channel!!.lock()
            heldIntraJvmLock = jvmLock
        } catch (t: Throwable) {
            channel?.close()
            channel = null
            jvmLock.unlock()
            throw t
        }
    }

    actual fun release() {
        try {
            lock?.release()
        } finally {
            lock = null
            channel?.close()
            channel = null
            heldIntraJvmLock?.unlock()
            heldIntraJvmLock = null
        }
    }

    private companion object {
        val intraJvmLocks = ConcurrentHashMap<String, ReentrantLock>()

        /** [ConcurrentHashMap.computeIfAbsent]-equivalent get-or-create, without
         * `computeIfAbsent` itself (API 24). [ConcurrentHashMap.putIfAbsent] has been available
         * since API 1: if two threads race to create a lock for the same [key], only one's
         * [ReentrantLock] actually gets stored, and both threads end up with that same instance. */
        fun jvmLockFor(key: String): ReentrantLock {
            intraJvmLocks[key]?.let { return it }
            val created = ReentrantLock()
            return intraJvmLocks.putIfAbsent(key, created) ?: created
        }
    }
}
