package com.retryjournal.queue

import com.retryjournal.queue.disk.DiskQueue
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import com.retryjournal.queue.FrozenHttpHeaders
import kotlinx.coroutines.test.runTest

class DiskQueueIosTest {
    private val dir = "/tmp/retry-journal-ios-test".toPath()

    @BeforeTest
    fun setUp() {
        com.ghost.serialization.Ghost.addRegistry(com.ghost.serialization.generated.GhostModuleRegistry_retry_journal.INSTANCE)
        FileSystem.SYSTEM.createDirectories(dir, mustCreate = false)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun testEnqueueAndPeek() = runTest {
        val queuePath = dir.resolve("queue.bin")
        val queue = DiskQueue(queuePath, FileSystem.SYSTEM)
        
        assertTrue(queue.isEmpty())
        
        queue.enqueue("POST", "/test", FrozenHttpHeaders.EMPTY, "hello".encodeToByteArray())
        assertEquals(1, queue.size())
        
        val entry = queue.peek()
        assertTrue(entry != null)
        assertEquals("POST", entry.meta.method)
        assertEquals("/test", entry.meta.url)
        assertEquals("hello", entry.body.decodeToString())
        
        queue.remove(entry.id)
        assertTrue(queue.isEmpty())
        queue.close()
    }
}
