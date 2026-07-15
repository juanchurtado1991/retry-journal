package com.retryjournal.engine

import com.retryjournal.freshTestDir
import com.retryjournal.TestCounter
import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.platform.currentTimeMillis
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
import okio.Sink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression tests from the final 1.0.0 architecture bug hunt. */
class BugHunt17Test {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: RetryJournalEngine

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("bug-hunt-17")
        queue = DiskQueue((dir.toString() + "/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dlq.bin").toPath()))
        engine = RetryJournalEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `flush recovery from a dead-letter journal skips HTTP`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DEAD_LETTERED,
        )
        val httpCalls = TestCounter(0)
        val client = HttpClient(MockEngine {
            httpCalls.incrementAndGet()
            respond("ok", HttpStatusCode.OK, headersOf())
        })

        val result = engine.flush(client)

        assertEquals(0, httpCalls.get())
        assertEquals(FlushResult(delivered = 0, deadLettered = 1, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
        assertEquals(1, deadLetterQueue.size())
    }

    @Test
    fun `flush recovery from a delivered journal skips HTTP`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        val httpCalls = TestCounter(0)
        val client = HttpClient(MockEngine {
            httpCalls.incrementAndGet()
            respond("ok", HttpStatusCode.OK, headersOf())
        })

        val result = engine.flush(client)

        assertEquals(0, httpCalls.get())
        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun getHeadStateIgnoresStaleClaimWhenHeadHasDeliveryJournal() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        ReplayClaim.write(
            queue.fileSystem,
            ReplayClaim.claimPath(queue.path),
            id.sequenceId,
            currentTimeMillis(),
        )

        val state = engine.getHeadState()

        assertTrue(state is QueueHeadState.AwaitingLocalRemoval)
        assertEquals(id, (state as QueueHeadState.AwaitingLocalRemoval).entry.id)
    }

    @Test
    fun diskQueueCloseForShutdownRejectsPeek() {
        runBlocking {
            queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
            queue.closeForShutdown()

            assertFailsWith<IllegalStateException> { queue.peek() }
        }
    }

    @Test
    fun prepareHeadForReplayReadyAfterFailedDeliveryJournalWrite() = runBlocking {
        val mainPath = (dir.toString() + "/claim-fail.bin").toPath()
        val failingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                if (file.name.contains(DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX)) {
                    throw IOException("journal write failed")
                }
                return super.sink(file, mustCreate)
            }
        }
        val failingQueue = DiskQueue(mainPath, failingFs)
        val failingEngine = RetryJournalEngine(failingQueue, deadLetterQueue)
        failingQueue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        failingEngine.flush(client)

        assertTrue(failingQueue.prepareHeadForReplay() is HeadReplayPrepareResult.Ready)
    }
}

