package com.retryjournal.queue

import com.retryjournal.peekAll
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueIndexSync
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DiskQueueIndexSyncTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("retry-journal-index-sync-test")
        queuePath = (dir.toString() + "/queue.bin").toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `a stale queue instance rescans when another instance appends to the same file`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        queueA.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())
        assertEquals(1, queueA.size())

        val queueB = DiskQueue(queuePath)
        queueB.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second".encodeToByteArray())

        assertEquals(2, queueA.size())
        val entries = queueA.peekAll()
        assertEquals(listOf("/first", "/second"), entries.map { it.meta.url })
    }

    @Test
    fun `refresh picks up an externally deleted queue file`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/only", FrozenHttpHeaders.EMPTY, "x".encodeToByteArray())
        FileSystem.SYSTEM.delete(queuePath)
        FileSystem.SYSTEM.delete(DiskQueueIndexSync.generationPath(queuePath), mustExist = false)

        assertEquals(0, queue.size())
    }

    @Test
    fun `a stale queue instance rescans when the generation counter advances without a size change`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        queueA.enqueue("POST", "/only", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        assertEquals(1, queueA.size())

        val genPath = DiskQueueIndexSync.generationPath(queuePath)
        FileSystem.SYSTEM.write(genPath) { writeUtf8("99") }

        assertEquals(1, queueA.size())
        val entry = queueA.peek()
        assertEquals("/only", entry?.meta?.url)
    }

    @Test
    fun `refresh rescans when the queue file grew but the generation counter did not advance`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        queueA.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())
        val queueB = DiskQueue(queuePath)
        assertEquals(1, queueB.size())

        queueA.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second".encodeToByteArray())

        val genPath = DiskQueueIndexSync.generationPath(queuePath)
        FileSystem.SYSTEM.write(genPath) { writeUtf8("1") }

        assertEquals(2, queueB.size())
        val entries = queueB.peekAll()
        assertEquals(listOf("/first", "/second"), entries.map { it.meta.url })
    }
}
