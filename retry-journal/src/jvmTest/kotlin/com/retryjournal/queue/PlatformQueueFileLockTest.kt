package com.retryjournal.queue

import com.retryjournal.queue.platform.PlatformQueueFileLock
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlatformQueueFileLockTest {

    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("platform-queue-file-lock-test")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir.toString().toPath(), mustExist = false)
    }

    @Test
    fun `acquire releases the intra-JVM lock when it fails partway through`() {
        // A directory at the lock path makes FileChannel.open(..., WRITE) fail after the
        // intra-JVM ReentrantLock has already been taken. If acquire() doesn't unlock it on that
        // failure path, every later acquire() for this same path in this JVM blocks forever.
        val lockNioPath = dir.resolve("queue.bin.lock")
        Files.createDirectory(lockNioPath)
        val lockPath = lockNioPath.toString().toPath()

        val failingLock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
        assertFailsWith<Exception> { failingLock.acquire() }

        Files.delete(lockNioPath)

        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                val secondLock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
                secondLock.acquire()
                secondLock.release()
            }
            // Fails loudly with a TimeoutException instead of hanging the whole suite if the
            // intra-JVM lock leaked.
            future.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Regression: the intra-JVM guard used to key on `toAbsolutePath().normalize()` alone, which
     * is purely lexical — a symlink pointing at the same real file got a different key, so two
     * threads could both pass the guard for what the OS treats as one file and race straight into
     * [java.nio.channels.OverlappingFileLockException] instead of serializing as documented. */
    @Test
    fun `acquire via a symlink serializes with acquire via the real path it points at`() {
        val realPath = dir.resolve("queue.bin.lock")
        Files.createFile(realPath)
        val symlinkPath = dir.resolve("queue-symlink.bin.lock")
        Files.createSymbolicLink(symlinkPath, realPath)

        val lockOnReal = PlatformQueueFileLock(realPath.toString().toPath(), FileSystem.SYSTEM)
        val lockOnSymlink = PlatformQueueFileLock(symlinkPath.toString().toPath(), FileSystem.SYSTEM)

        lockOnReal.acquire()
        val executor = Executors.newSingleThreadExecutor()
        try {
            // acquire() and release() must run on the same thread — ReentrantLock (unlike
            // synchronized) throws IllegalMonitorStateException if a different thread than the
            // one that locked it calls unlock().
            val future = executor.submit {
                lockOnSymlink.acquire()
                lockOnSymlink.release()
            }
            // Blocked behind the intra-JVM guard rather than racing straight into
            // FileChannel.lock() (which throws instead of waiting) or succeeding concurrently.
            assertFailsWith<TimeoutException> { future.get(500, TimeUnit.MILLISECONDS) }

            lockOnReal.release()
            future.get(5, TimeUnit.SECONDS) // now proceeds, acquiring and releasing on the executor thread
        } finally {
            executor.shutdownNow()
        }
    }

    /** Regression: `ensureExists` originally used [Files.createFile], whose `CREATE_NEW`/`EXCL`
     * semantics treat a symlink as "already there" without following it — so for two *dangling*
     * symlinks pointing at the same not-yet-created target, each one's `toRealPath()` still failed
     * (target never materialized) and fell back to each symlink's own distinct lexical path,
     * reproducing the exact key-inconsistency bug this class exists to prevent. */
    @Test
    fun `acquire via two different dangling symlinks to the same target serializes instead of racing`() {
        val target = dir.resolve("queue.bin.lock") // never created directly — only the symlinks are
        val symlinkA = dir.resolve("queue-a.lock")
        val symlinkB = dir.resolve("queue-b.lock")
        Files.createSymbolicLink(symlinkA, target)
        Files.createSymbolicLink(symlinkB, target)

        val lockA = PlatformQueueFileLock(symlinkA.toString().toPath(), FileSystem.SYSTEM)
        val lockB = PlatformQueueFileLock(symlinkB.toString().toPath(), FileSystem.SYSTEM)

        lockA.acquire()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                lockB.acquire()
                lockB.release()
            }
            assertFailsWith<TimeoutException> { future.get(500, TimeUnit.MILLISECONDS) }

            lockA.release()
            future.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    /** Regression: [PlatformQueueFileLock]'s `ensureExists` used to unconditionally open-then-close
     * a throwaway probe [FileChannel] on every `acquire()`, even when the lock file already
     * existed. The JDK's own `FileLock` docs warn that on some platforms, closing *any* channel on
     * a file releases *every* lock the JVM holds on it — via that channel or any other one open on
     * the same file — so that probe could silently release another thread's still-held real OS
     * lock. This only materializes via open+close for a file that doesn't exist yet (nothing could
     * hold a lock on a file that didn't exist a moment ago), and skips straight past for one that
     * already does. */
    @Test
    fun `a second acquire on an already-existing path does not release the first holder's real OS lock`() {
        val lockNioPath = dir.resolve("queue.bin.lock")
        val lockPath = lockNioPath.toString().toPath()

        val firstLock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
        firstLock.acquire()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit {
                val secondLock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
                secondLock.acquire()
                secondLock.release()
            }
            // Blocked behind the intra-JVM guard, same as the other serialization tests — but the
            // point here is what happens to the *first* holder's real lock while this is blocked.
            assertFailsWith<TimeoutException> { future.get(500, TimeUnit.MILLISECONDS) }

            // A raw third-party probe on the same file, in this same JVM, must still see the lock
            // as held — OverlappingFileLockException is exactly how the JVM reports "already
            // locked here" (see this file's own top-level doc). If the second acquire()'s
            // materialization step had silently released the first holder's real FileLock, this
            // probe would succeed instead of throwing.
            FileChannel.open(lockNioPath, StandardOpenOption.WRITE).use { probe ->
                assertFailsWith<java.nio.channels.OverlappingFileLockException> { probe.tryLock() }
            }

            firstLock.release()
            future.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }
}
