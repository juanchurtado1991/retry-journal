package com.retryjournal.queue.platform

import okio.FileSystem
import okio.Path
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path as NioPath
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
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
        val nioPath: NioPath = Paths.get(lockPath.toString())
        Files.createDirectories(nioPath.parent ?: nioPath)

        val jvmLock = intraJvmLocks.computeIfAbsent(canonicalKeyFor(nioPath)) {
            ReentrantLock()
        }
        jvmLock.lock()
        try {
            channel = FileChannel.open(
                nioPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
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

        /** `normalize()` only collapses `.`/`..` lexically — it does not resolve symlinks or the
         * case-insensitive aliasing common on macOS/Windows filesystems, so two different path
         * strings that point at the same real file on disk could still key [intraJvmLocks]
         * separately, letting two threads past the intra-JVM lock for what the OS treats as one
         * file and straight into [java.nio.channels.OverlappingFileLockException].
         * [NioPath.toRealPath] resolves both, but requires the file to already exist — falls back
         * to the previous `normalize()`-only key when it doesn't yet (first `acquire()` on a fresh
         * path) or resolution otherwise fails. */
        fun canonicalKeyFor(path: NioPath): String {
            val normalized = path.toAbsolutePath().normalize()
            return try {
                normalized.toRealPath().toString()
            } catch (_: java.io.IOException) {
                normalized.toString()
            }
        }
    }
}
