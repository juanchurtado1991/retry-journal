package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.platform.PlatformQueueFileLock
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlatformQueueFileLockIosTest {
    private val dir = "/tmp/ghost-sync-lock-ios-test".toPath()

    @BeforeTest
    fun setUp() {
        FileSystem.SYSTEM.createDirectories(dir, mustCreate = false)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun testLockAcquireAndRelease() {
        val lockPath = dir.resolve("lock.bin")
        val lock = PlatformQueueFileLock(lockPath, FileSystem.SYSTEM)
        
        lock.acquire()
        lock.release()
    }
}
