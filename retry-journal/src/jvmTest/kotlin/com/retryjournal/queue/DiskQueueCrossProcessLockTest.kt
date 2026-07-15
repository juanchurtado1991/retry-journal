package com.retryjournal.queue

import com.retryjournal.freshTestDir
import com.retryjournal.queue.platform.PlatformQueueFileLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every other lock test in this module — including "two DiskQueue instances on the same path
 * serialize concurrent writes safely" in [DiskQueueTest] — runs both contenders as coroutines
 * inside one JVM process. That only proves [PlatformQueueFileLock]'s intra-JVM `ReentrantLock`
 * layer works; it says nothing about the actual `FileChannel.lock()` call underneath, which is
 * the part [PlatformQueueFileLock]'s own doc warns is JVM-wide *in theory* but in practice throws
 * `OverlappingFileLockException` instead of blocking when contended from the same process — the
 * exact bug the `ReentrantLock` layer works around. This test launches a real second `java`
 * process to contend for the same lock file, so it actually exercises cross-process blocking
 * instead of assuming it works because the in-process workaround does.
 */
class DiskQueueCrossProcessLockTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("cross-process-lock-test")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `PlatformQueueFileLock blocks a genuinely separate OS process, not just another thread`() = runBlocking {
        val lockPath = dir.resolve("queue.bin.lock")
        val readyMarker = dir.resolve("ready.marker")
        val releaseMarker = dir.resolve("release.marker")

        val javaBin = System.getProperty("java.home") + "/bin/java"
        val classpath = System.getProperty("java.class.path")
        val process = ProcessBuilder(
            javaBin,
            "-cp",
            classpath,
            "com.retryjournal.queue.LockHolderProcessKt",
            lockPath.toString(),
            readyMarker.toString(),
            releaseMarker.toString(),
        ).redirectErrorStream(true).start()

        try {
            val childHeldTheLock = withTimeoutOrNull(15_000) {
                while (!FileSystem.SYSTEM.exists(readyMarker)) {
                    delay(20)
                }
                true
            } ?: false
            assertTrue(childHeldTheLock, "child process never signaled that it acquired the lock")

            // The child process genuinely holds the OS-level lock now. Acquiring the SAME lock
            // from this process must block — if it completes immediately, cross-process locking
            // isn't actually being enforced. Run acquire()/release() on one dedicated raw Thread
            // (not a coroutine on a shared dispatcher pool): PlatformQueueFileLock's intra-JVM
            // layer is backed by a ReentrantLock, and ReentrantLock.unlock() requires the exact
            // same thread that called lock() — a coroutine can legitimately resume on a different
            // pool thread than the one that suspended, which would throw
            // IllegalMonitorStateException here even though the production code never splits
            // acquire/release across a suspension point like that.
            val parentLock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
            val acquired = CompletableDeferred<Unit>()
            val releaseNow = CompletableDeferred<Unit>()
            val lockThread = Thread {
                parentLock.acquire()
                acquired.complete(Unit)
                runBlocking { releaseNow.await() }
                parentLock.release()
            }.apply {
                isDaemon = true
                start()
            }

            val blockedAsExpected = withTimeoutOrNull(1_000) { acquired.await() } == null
            assertTrue(blockedAsExpected, "parent process's acquire() should have blocked while the child held the lock")

            // Let the child release, then confirm the parent's pending acquire actually unblocks.
            FileSystem.SYSTEM.write(releaseMarker) { writeUtf8("go") }
            withTimeout(10_000) { acquired.await() }

            releaseNow.complete(Unit)
            lockThread.join(10_000)
            assertTrue(!lockThread.isAlive, "lock-holding thread should have released and finished")

            process.waitFor()
            Unit
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
}
