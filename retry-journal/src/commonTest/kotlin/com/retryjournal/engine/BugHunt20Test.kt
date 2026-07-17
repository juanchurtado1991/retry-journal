package com.retryjournal.engine

import com.retryjournal.freshTestDir
import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression tests from bug hunt round 20. */
class BugHunt20Test {

    private lateinit var dir: Path
    private lateinit var deadLetterQueue: DeadLetterQueue

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("bug-hunt-20")
        deadLetterQueue = DeadLetterQueue(
            DiskQueue((dir.toString() + "/main.bin").toPath()),
            DiskQueue((dir.toString() + "/dlq.bin").toPath()),
        )
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    /** [HeadReplayExecutor.finishDeliveredFromJournal] used to be the only one of its four sibling
     * "finish" functions with no try/catch around [DeliveryJournal.delete]/`onProgress` — a failure
     * there (recovering a 2xx already recorded by a previous flush) escaped `flush()` as an
     * unhandled exception instead of resolving to a [FlushResult], and left the delivery journal
     * file orphaned on disk with no [FlushResult.persistenceFailed] signal. */
    @Test
    fun `flush recovering a delivered journal does not throw when cleanup fails and reports persistenceFailed`() = runBlocking {
        val mainPath = (dir.toString() + "/journal-delete-fails.bin").toPath()
        val plainQueue = DiskQueue(mainPath)
        val id = plainQueue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(plainQueue.fileSystem, mainPath, id.sequenceId, DeliveryJournal.OUTCOME_DELIVERED)

        val journalPath = (mainPath.toString() + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX + id.sequenceId).toPath()
        val failingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path == journalPath) {
                    throw IOException("simulated journal cleanup failure")
                }
                super.delete(path, mustExist)
            }
        }
        val failingQueue = DiskQueue(mainPath, failingFs)
        val engine = RetryJournalEngine(failingQueue, deadLetterQueue)
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        // Must not throw — before the fix, the injected IOException escaped flush() unhandled.
        val result = engine.flush(client)

        assertEquals(0, result.delivered, "the completion step failed, so this attempt does not count as delivered")
        assertTrue(result.stoppedEarly)
        assertTrue(result.persistenceFailed, "the journal already said Delivered before cleanup failed")
        assertTrue(failingQueue.isEmpty(), "the head entry itself was already removed before cleanup failed")
    }

    /** Companion to the test above: when [DeliveryJournal.write] itself fails on a first-time 2xx
     * (nothing durably recorded yet), [FlushResult.persistenceFailed] must be `false` — the next
     * `flush()` safely resends from scratch instead of only retrying local cleanup. */
    @Test
    fun `flush reports persistenceFailed false when the delivery journal write itself fails`() = runBlocking {
        val mainPath = (dir.toString() + "/journal-write-fails.bin").toPath()
        val failingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun sink(file: Path, mustCreate: Boolean): okio.Sink {
                if (file.name.contains(DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX)) {
                    throw IOException("simulated journal write failure")
                }
                return super.sink(file, mustCreate)
            }
        }
        val failingQueue = DiskQueue(mainPath, failingFs)
        failingQueue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val engine = RetryJournalEngine(failingQueue, deadLetterQueue)
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(0, result.delivered)
        assertTrue(result.stoppedEarly)
        assertTrue(!result.persistenceFailed, "nothing was durably recorded yet, so this is a plain retry-from-scratch case")
    }
}
