package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.platform.PlatformQueueFileLock
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
}
