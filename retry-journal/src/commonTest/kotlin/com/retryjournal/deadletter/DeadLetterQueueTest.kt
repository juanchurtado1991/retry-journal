package com.retryjournal.deadletter

import com.retryjournal.freshTestDir
import com.retryjournal.peekAll
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeadLetterQueueTest {

    private lateinit var dir: Path
    private lateinit var mainQueue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("retry-journal-dlq-test")
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
        // Mirrors what happens if the process dies between RetryJournalEngine.flush's
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
    fun `same headers in a different order collapse to one dead-letter entry`() = runBlocking {
        val id1 = deadLetterQueue.record(
            "POST",
            "/rejected",
            FrozenHttpHeaders.of("A" to "1", "B" to "2"),
            "payload".encodeToByteArray(),
        )
        val id2 = deadLetterQueue.record(
            "POST",
            "/rejected",
            FrozenHttpHeaders.of("B" to "2", "A" to "1"),
            "payload".encodeToByteArray(),
        )

        assertEquals(id1, id2)
        assertEquals(1, deadLetterQueue.peekAll().size)
    }

    @Test
    fun `same method url and body but different headers are not treated as a duplicate`() = runBlocking {
        // A shared Authorization/tenant header is exactly the kind of difference that makes two
        // otherwise-identical-looking requests genuinely distinct — collapsing them would hide
        // one of them from the dead-letter UI entirely.
        val id1 = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("Authorization" to "token-a"), "payload".encodeToByteArray())
        val id2 = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("Authorization" to "token-b"), "payload".encodeToByteArray())

        assertTrue(id1 != id2)
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
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)

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
    fun `recovery finishes a retry when the journal exists but the entry is still in dead-letter storage`() = runBlocking {
        // Simulates a crash after DeadLetterRetryJournal.write but before storage.remove in retry().
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("X" to "1"), "payload".encodeToByteArray())
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)

        assertEquals(1, dlqStorage.size())

        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.size()

        assertTrue(dlqStorage2.isEmpty())
        val recovered = mainQueue2.peek()
        kotlin.test.assertNotNull(recovered)
        assertEquals("/rejected", recovered.meta.url)
        assertEquals("payload", recovered.body.decodeToString())
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }

    @Test
    fun `recovery does not duplicate an entry already present on the main queue`() = runBlocking {
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        mainQueue.enqueue("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)
        dlqStorage.remove(entry.id)

        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.size()

        assertEquals(1, mainQueue2.size())
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }

    @Test
    fun `recovery does not skip re-enqueue when the main queue's match differs only by headers`() = runBlocking {
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.of("Authorization" to "token-a"), "payload".encodeToByteArray())
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        // Same method/url/body as the journal below, but a different Authorization — recovery
        // must not mistake this for the same request and skip re-enqueueing the real one.
        mainQueue.enqueue("POST", "/rejected", FrozenHttpHeaders.of("Authorization" to "token-b"), "payload".encodeToByteArray())

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)
        dlqStorage.remove(entry.id)

        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.size()

        val recovered = mainQueue2.peekAll()
        assertEquals(2, recovered.size)
        assertEquals(setOf("token-a", "token-b"), recovered.map { it.meta.headers.findValue("Authorization") }.toSet())
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }

    @Test
    fun `a corrupted retry journal is deleted during recovery instead of lingering forever`() = runBlocking {
        val entryId = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!

        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)
        dlqStorage.remove(entry.id)

        // Truncate the journal mid-record so it can never parse back — nothing about a future
        // restart makes these same bytes readable again.
        val bytes = FileSystem.SYSTEM.read(journalFile) { readByteArray() }
        FileSystem.SYSTEM.write(journalFile) { write(bytes.copyOfRange(0, bytes.size / 2)) }

        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.size() // triggers recovery

        assertTrue(!FileSystem.SYSTEM.exists(journalFile), "an unreadable journal must not be left behind to be re-attempted forever")
        assertTrue(mainQueue2.isEmpty())
    }

    @Test
    fun `discard deletes an orphan retry journal instead of recovering it onto the main queue`() = runBlocking {
        val entryId = deadLetterQueue.record(
            "POST",
            "/rejected",
            FrozenHttpHeaders.EMPTY,
            "payload".encodeToByteArray(),
        )
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entry = dlqStorage.peek()!!
        val journalFile = (dlqStorage.path.toString() + ".retry." + entryId.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, entryId.value, entry)
        dlqStorage.remove(entry.id)

        val mainQueue2 = DiskQueue((dir.toString() + "/main-discard-journal.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        deadLetterQueue2.discard(entryId)

        assertTrue(mainQueue2.isEmpty())
        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
        assertEquals(0, deadLetterQueue2.size())
    }

    @Test
    fun `discard succeeds when a pending retry journal for a different id needs recovery first`() = runBlocking {
        // Regression: discard() used to call ensureRecovered() *inside* the dlqOpsProcessLock it
        // had already acquired. If a pending retry journal for a *different* id needed
        // recovering, recoverPendingRetries() would try to acquire that same
        // PlatformQueueFileLock again — not reentrant, so this threw
        // OverlappingFileLockException on JVM and leaked the outer FileChannel.
        val idA = deadLetterQueue.record("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = deadLetterQueue.record("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        // Simulate a crash mid-retry(idB): its journal is written and it's removed from storage,
        // but it never finished being enqueued onto the main queue — recovery must process this
        // before the discard below runs.
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val entryB = dlqStorage.get(QueueEntryId(idB.value))!!
        val journalFile = (dlqStorage.path.toString() + ".retry." + idB.value).toPath()
        DeadLetterRetryJournal.write(dlqStorage.fileSystem, journalFile, idB.value, entryB)
        dlqStorage.remove(entryB.id)

        // Fresh instance so ensureRecovered() has not run on it yet.
        val mainQueue2 = DiskQueue((dir.toString() + "/main.bin").toPath())
        val dlqStorage2 = DiskQueue((dir.toString() + "/dead-letter.bin").toPath())
        val deadLetterQueue2 = DeadLetterQueue(mainQueue2, dlqStorage2)

        // Must not throw — before the fix, this threw OverlappingFileLockException on JVM.
        deadLetterQueue2.discard(idA)

        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
        val recovered = mainQueue2.peek()
        kotlin.test.assertNotNull(recovered)
        assertEquals("/b", recovered.meta.url)
    }

    @Test
    fun `recovery deletes retry journals with unparseable ids`() = runBlocking {
        val journalFile = (dir.toString() + "/dead-letter.bin.retry.notanumber").toPath()
        FileSystem.SYSTEM.write(journalFile) { writeUtf8("garbage") }

        deadLetterQueue.size()

        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
    }

    @Test
    fun `closeForShutdown rejects new discard calls`() {
        runBlocking {
            deadLetterQueue.closeForShutdown()
            assertFailsWith<IllegalStateException> {
                deadLetterQueue.discard(DeadLetterEntryId(1L))
            }
        }
    }

    @Test
    fun `retry after discard does not re-enqueue onto the main queue`() = runBlocking {
        val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())

        deadLetterQueue.discard(id)
        deadLetterQueue.retry(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `concurrent discard and retry cannot resurrect a discarded entry`() = runBlocking {
        coroutineScope {
            repeat(20) { iteration ->
                val id = deadLetterQueue.record(
                    "POST",
                    "/rejected-$iteration",
                    FrozenHttpHeaders.EMPTY,
                    "payload".encodeToByteArray(),
                )
                List(8) { worker ->
                    launch(Dispatchers.Default) {
                        if (worker % 2 == 0) {
                            deadLetterQueue.discard(id)
                        } else {
                            deadLetterQueue.retry(id)
                        }
                    }
                }.forEach { it.join() }
                assertTrue(deadLetterQueue.peekAll().isEmpty())
            }
        }
    }

    @Test
    fun `concurrent record of the same request deduplicates to one entry`() = runBlocking {
        coroutineScope {
            repeat(20) {
                launch(Dispatchers.Default) {
                    deadLetterQueue.record(
                        "POST",
                        "/rejected",
                        FrozenHttpHeaders.of("X" to "1"),
                        "payload".encodeToByteArray(),
                    )
                }
            }
        }
        assertEquals(1, deadLetterQueue.peekAll().size)
    }

    @Test
    fun `retry recovers onto the main queue on the same instance when the first enqueue fails`() = runBlocking {
        var mainEnqueueAttempts = 0
        val mainPath = (dir.toString() + "/main.bin").toPath()
        val failingMainFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): okio.Sink {
                if (file == mainPath) {
                    mainEnqueueAttempts++
                    if (mainEnqueueAttempts == 1) {
                        throw IOException("disk full")
                    }
                }
                return super.appendingSink(file, mustExist)
            }
        }
        mainQueue = DiskQueue(mainPath, failingMainFs)
        deadLetterQueue = DeadLetterQueue(mainQueue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))

        val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        deadLetterQueue.retry(id)

        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertEquals("payload", mainQueue.peek()?.body?.decodeToString())
    }

    @Test
    fun `a corrupted header count in a journal does not crash recovery`() = runBlocking {
        // Hand-crafted directly, bypassing writeJournal, so the header count field can be set to
        // something no real write would ever produce: ArrayList(headersSize) must not trust it.
        val journalFile = (dir.toString() + "/dead-letter.bin.retry.7").toPath()
        FileSystem.SYSTEM.write(journalFile) {
            writeDecimalLong(7L)
            writeByte('\n'.code)
            writeInt(4)
            writeUtf8("POST")
            writeInt(1)
            writeUtf8("/")
            writeInt(Int.MAX_VALUE) // corrupted header count
        }

        deadLetterQueue.size() // triggers recovery; must not throw OutOfMemoryError

        assertTrue(!FileSystem.SYSTEM.exists(journalFile))
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun `read treats an OutOfMemoryError from the file system as an unreadable journal not a crash`() {
        val journalFile = (dir.toString() + "/dead-letter.bin.retry.9").toPath()
        FileSystem.SYSTEM.write(journalFile) { writeUtf8("never read — the throwing file system below fails first") }

        val throwingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): Source {
                if (file == journalFile) {
                    throw OutOfMemoryError("simulated allocation failure reading a corrupted journal")
                }
                return super.source(file)
            }
        }

        // A per-field bound doesn't cap the *sum* across many headers in one journal (see
        // DeadLetterRetryJournal's own doc) — a real OutOfMemoryError from a corrupted journal is a
        // Throwable, not an Exception, and must be swallowed like any other unreadable journal
        // instead of propagating up through recovery.
        assertNull(DeadLetterRetryJournal.read(throwingFs, journalFile))
    }

    @Test
    fun `retry rethrows cancellation instead of recovering onto the main queue inline`() {
        runBlocking {
            val mainPath = (dir.toString() + "/main.bin").toPath()
            val cancellingMainFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun appendingSink(file: Path, mustExist: Boolean): okio.Sink {
                    if (file == mainPath) {
                        throw CancellationException("cancelled during retry enqueue")
                    }
                    return super.appendingSink(file, mustExist)
                }
            }
            mainQueue = DiskQueue(mainPath, cancellingMainFs)
            deadLetterQueue = DeadLetterQueue(mainQueue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))

            val id = deadLetterQueue.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())

            assertFailsWith<CancellationException> {
                deadLetterQueue.retry(id)
            }
            assertTrue(mainQueue.isEmpty())
        }
    }

    @Test
    fun `concurrent retry from two queue instances enqueues at most once onto the main queue`() = runBlocking {
        val mainPath = (dir.toString() + "/main.bin").toPath()
        val dlqPath = (dir.toString() + "/dead-letter.bin").toPath()
        mainQueue = DiskQueue(mainPath)
        val dlqA = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))
        val dlqB = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))

        val id = dlqA.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())

        coroutineScope {
            listOf(
                async(Dispatchers.Default) { dlqA.retry(id) },
                async(Dispatchers.Default) { dlqB.retry(id) },
            ).awaitAll()
        }

        assertTrue(dlqA.peekAll().isEmpty())
        assertEquals(1, mainQueue.size())
        assertEquals("payload", mainQueue.peek()?.body?.decodeToString())
    }

    @Test
    fun `concurrent record from two queue instances deduplicates to a single dead-letter entry`() = runBlocking {
        val mainPath = (dir.toString() + "/main.bin").toPath()
        val dlqPath = (dir.toString() + "/dead-letter.bin").toPath()
        mainQueue = DiskQueue(mainPath)
        val dlqA = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))
        val dlqB = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))

        coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    dlqA.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
                },
                async(Dispatchers.Default) {
                    dlqB.record("POST", "/rejected", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
                },
            ).awaitAll()
        }

        assertEquals(1, dlqA.peekAll().size)
    }

    @Test
    fun `recovery from two queue instances enqueues at most once onto the main queue`() = runBlocking {
        val mainPath = (dir.toString() + "/main.bin").toPath()
        val dlqPath = (dir.toString() + "/dead-letter.bin").toPath()
        mainQueue = DiskQueue(mainPath)
        val dlqStorage = DiskQueue(dlqPath)

        val id = DeadLetterEntryId(11L)
        val journalFile = (dlqPath.toString() + ".retry." + id.value).toPath()
        DeadLetterRetryJournal.write(
            dlqStorage.fileSystem,
            journalFile,
            id.value,
                QueueEntry(
                QueueEntryId(id.value),
                FrozenHttpRequestMeta("POST", "/rejected", FrozenHttpHeaders.EMPTY, 0L),
                "payload".encodeToByteArray(),
            ),
        )

        val dlqA = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))
        val dlqB = DeadLetterQueue(mainQueue, DiskQueue(dlqPath))

        coroutineScope {
            listOf(
                async(Dispatchers.Default) { dlqA.size() },
                async(Dispatchers.Default) { dlqB.size() },
            ).awaitAll()
        }

        assertTrue(!dlqStorage.fileSystem.exists(journalFile))
        assertEquals(1, mainQueue.size())
    }
}
