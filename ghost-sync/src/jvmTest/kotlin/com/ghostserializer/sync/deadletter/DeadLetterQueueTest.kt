package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.QueueEntry
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
        deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "bad-payload".encodeToByteArray())

        val entries = deadLetterQueue.peekAll()

        assertEquals(1, entries.size)
        assertEquals("/rejected", entries[0].meta.url)
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `retry moves the entry back onto the main queue and off the dead-letter queue`() = runBlocking {
        val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("X" to "1"), "payload".encodeToByteArray())

        deadLetterQueue.retry(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        val requeued = mainQueue.peek()
        assertEquals("/rejected", requeued?.meta?.url)
        assertEquals("payload", requeued?.body?.decodeToString())
    }

    @Test
    fun `discard drops the entry for good without touching the main queue`() = runBlocking {
        val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, ByteArray(0))

        deadLetterQueue.discard(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `size reflects records and removals without decoding any record`() = runBlocking {
        assertEquals(0, deadLetterQueue.size())

        val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
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

    @Test
    fun `recording the same request twice does not duplicate the dead-letter entry`() = runBlocking {
        // Mirrors what happens if the process dies between GhostSyncEngine.flush's
        // deadLetterQueue.record(...) and its queue.remove(entry.id): the entry is still live on
        // the main queue, so the next flush replays it and — if the server still rejects it —
        // records it again. That must collapse into the same dead-letter entry, not a duplicate.
        val id1 = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("X" to "1"), "payload".encodeToByteArray())
        val id2 = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("X" to "1"), "payload".encodeToByteArray())

        assertEquals(id1, id2)
        assertEquals(1, deadLetterQueue.peekAll().size)
    }

    @Test
    fun `recording a genuinely different request after a duplicate is not swallowed`() = runBlocking {
        val id1 = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        val id3 = deadLetterQueue.record("POST", "/other", FrozenHttpHeaders.EMPTY, "different-payload".encodeToByteArray())

        assertTrue(id1 != id3)
        assertEquals(2, deadLetterQueue.peekAll().size)
    }

    @Test
    fun `recovery processes pending retry journals on initialization`() = runBlocking {
        // Record an entry
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("X" to "1"), "payload".encodeToByteArray())

        // We will simulate a crash right after the entry has been removed from storage but before mainQueue.enqueue completes.
        // To do this, we manually write the retry journal file with the entry content, and remove it from the dead-letter queue storage.
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()

        // Let's write the journal file using reflection
        val writeJournalMethod = DeadLetterQueue::class.java.declaredMethods.first { it.name == "writeJournal" }.apply {
            isAccessible = true
        }
        writeJournalMethod.invoke(deadLetterQueue, journalFile, entryId.value, entry)

        // Remove from storage to simulate the "removed from storage" state of the crash
        dlqStorage.remove(entry.id)
        assertTrue(dlqStorage.isEmpty())

        // Now create a fresh main queue and dead letter queue (simulating a crash restart)
        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())

        assertTrue(mainQueue2.isEmpty())

        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        // Accessing the queue should trigger recovery
        assertEquals(0, deadLetterQueue2.size())

        // The entry should be recovered and present in mainQueue2!
        val recovered = mainQueue2.peek()
        kotlin.test.assertNotNull(recovered)
        assertEquals("/rejected", recovered.meta.url)
        assertEquals("payload", recovered.body.decodeToString())

        // The journal file should have been cleaned up
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }

    @Test
    fun `recovery does not duplicate an entry already present on the main queue`() = runBlocking {
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        mainQueue.enqueue("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        val writeJournalMethod = DeadLetterQueue::class.java.declaredMethods.first { it.name == "writeJournal" }.apply {
            isAccessible = true
        }
        writeJournalMethod.invoke(deadLetterQueue, journalFile, entryId.value, entry)
        dlqStorage.remove(entry.id)

        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.size()

        assertEquals(1, mainQueue2.size())
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }
}
