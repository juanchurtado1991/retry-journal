package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueue
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterQueueTest {

    private lateinit var dir: Path
    private lateinit var mainQueue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-dlq-test").toString().toPath()
        mainQueue = DiskQueue((dir.toString() + "/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(mainQueue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `a recorded entry shows up in peekAll and not on the main queue`() = runBlocking {
        deadLetterQueue.record("POST", "/rejected", emptyMap(), "bad-payload".encodeToByteArray())

        val entries = deadLetterQueue.peekAll()

        assertEquals(1, entries.size)
        assertEquals("/rejected", entries[0].meta.url)
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `retry moves the entry back onto the main queue and off the dead-letter queue`() = runBlocking {
        val id = deadLetterQueue.record("POST", "/rejected", mapOf("X" to "1"), "payload".encodeToByteArray())

        deadLetterQueue.retry(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        val requeued = mainQueue.peek()
        assertEquals("/rejected", requeued?.meta?.url)
        assertEquals("payload", requeued?.body?.decodeToString())
    }

    @Test
    fun `discard drops the entry for good without touching the main queue`() = runBlocking {
        val id = deadLetterQueue.record("POST", "/rejected", emptyMap(), ByteArray(0))

        deadLetterQueue.discard(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `size reflects records and removals without decoding any record`() = runBlocking {
        assertEquals(0, deadLetterQueue.size())

        val id = deadLetterQueue.record("POST", "/rejected", emptyMap(), "bad".encodeToByteArray())
        assertEquals(1, deadLetterQueue.size())

        deadLetterQueue.discard(id)
        assertEquals(0, deadLetterQueue.size())
    }

    @Test
    fun `retry and discard are no-ops for an unknown id`() = runBlocking {
        deadLetterQueue.retry(DeadLetterEntryId(42L))
        deadLetterQueue.discard(DeadLetterEntryId(42L))

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(mainQueue.isEmpty())
        assertNull(mainQueue.peek())
    }
}
