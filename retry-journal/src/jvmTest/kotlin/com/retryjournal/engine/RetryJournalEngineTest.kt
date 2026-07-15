package com.retryjournal.engine

import com.retryjournal.peekAll
import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.DeliveryJournalReadResult
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import com.retryjournal.client.RetryJournalOfflineQueuePlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.ForwardingSink
import okio.Sink
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryJournalEngineTest {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: RetryJournalEngine

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("retry-journal-engine-test").toString().toPath()
        queue = DiskQueue(("$dir/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))
        engine = RetryJournalEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `concurrent flush calls replay each queued entry exactly once`() = runBlocking {
        repeat(3) { index ->
            queue.enqueue(
                "POST",
                "https://example.com/$index",
                FrozenHttpHeaders.EMPTY,
                "payload-$index".encodeToByteArray(),
            )
        }
        val replayCount = AtomicInteger(0)
        val client = HttpClient(
            MockEngine {
                replayCount.incrementAndGet()
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )

        coroutineScope {
            launch { engine.flush(client) }
            launch { engine.flush(client) }
        }

        assertEquals(3, replayCount.get())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a 2xx response removes the entry from the queue and counts as delivered`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a 4xx response moves the entry to the dead-letter queue and keeps flushing`() = runBlocking {
        queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        queue.enqueue("POST", "https://example.com/good", FrozenHttpHeaders.EMPTY, "good".encodeToByteArray())
        val client = HttpClient(
            MockEngine { request ->
                val status = if (request.url.encodedPath == "/bad") HttpStatusCode.BadRequest else HttpStatusCode.OK
                respond("", status, headersOf())
            },
        )

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 1, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
        val deadLettered = deadLetterQueue.peekAll()
        assertEquals(1, deadLettered.size)
        assertEquals("https://example.com/bad", deadLettered[0].meta.url)
    }

    @Test
    fun `a 5xx response stops the loop and leaves the entry untouched for the next flush`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertEquals(id, queue.peek()?.id)
    }

    @Test
    fun `a network failure stops the loop without touching the dead-letter queue`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { throw IOException("connection reset") })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(!queue.isEmpty())
    }

    @Test
    fun `onProgress fires once per entry, in order, as each one actually resolves`() = runBlocking {
        val idBad = queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        val idGood = queue.enqueue("POST", "https://example.com/good", FrozenHttpHeaders.EMPTY, "good".encodeToByteArray())
        val client = HttpClient(
            MockEngine { request ->
                val status = if (request.url.encodedPath == "/bad") HttpStatusCode.BadRequest else HttpStatusCode.OK
                respond("", status, headersOf())
            },
        )

        val progress = mutableListOf<FlushProgress>()
        engine.flush(client) { progress.add(it) }

        assertEquals(listOf(FlushProgress.DeadLettered(idBad), FlushProgress.Delivered(idGood)), progress)
    }

    @Test
    fun `an empty queue flushes instantly with no work done`() = runBlocking {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = false), result)
    }

    @Test
    fun `an unexpected runtime exception during replay stops early without dead-lettering`() = runBlocking {
        queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/bad") {
                throw IOException("transport failure during replay")
            } else {
                respond("ok", HttpStatusCode.OK, headersOf())
            }
        })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(!queue.isEmpty())
        assertTrue(deadLetterQueue.peekAll().isEmpty())
    }

    @Test
    fun `a 408 response stops early and leaves the entry on the main queue`() = runBlocking {
        queue.enqueue("POST", "https://example.com/slow", FrozenHttpHeaders.EMPTY, "slow".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.RequestTimeout, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(!queue.isEmpty())
        assertTrue(deadLetterQueue.peekAll().isEmpty())
    }

    @Test
    fun `a 429 response stops early and leaves the entry on the main queue`() = runBlocking {
        queue.enqueue("POST", "https://example.com/throttled", FrozenHttpHeaders.EMPTY, "payload".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(!queue.isEmpty())
        assertTrue(deadLetterQueue.peekAll().isEmpty())
    }

    @Test
    fun `replay applies the queued Content-Type header on the wire`() = runBlocking {
        queue.enqueue(
            "POST",
            "https://example.com/typed",
            FrozenHttpHeaders.of(HttpHeaders.ContentType to "application/x-ghost"),
            "ghost-bytes".encodeToByteArray(),
        )
        var replayedContentType: String? = null
        val client = HttpClient(
            MockEngine { request ->
                replayedContentType = (request.body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    ?.contentType
                    ?.toString()
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )

        engine.flush(client)

        assertEquals("application/x-ghost", replayedContentType)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a stored HTTP method that no longer parses does not permanently stall the queue`() = runBlocking {
        // Same stall pattern Content-Type had before parseContentTypeOrNull — HttpMethod.parse()
        // throwing here would wedge every future flush() behind this entry forever.
        queue.enqueue(
            "NOT_A_REAL_METHOD",
            "https://example.com/malformed-method",
            FrozenHttpHeaders.EMPTY,
            "body".encodeToByteArray(),
        )
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a stored Content-Type that no longer parses does not permanently stall the queue`() = runBlocking {
        // flush() always starts from the oldest entry — if ContentType.parse() threw here
        // uncaught, this entry (and everything queued after it) would never get another chance
        // to flush, forever.
        queue.enqueue(
            "POST",
            "https://example.com/malformed",
            FrozenHttpHeaders.of(HttpHeaders.ContentType to "not-a-content-type"),
            "body".encodeToByteArray(),
        )
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `concurrent flush from two queue instances on the same file replays each entry once`() = runBlocking {
        repeat(3) { index ->
            queue.enqueue(
                "POST",
                "https://example.com/$index",
                FrozenHttpHeaders.EMPTY,
                "payload-$index".encodeToByteArray(),
            )
        }
        val replayCount = AtomicInteger(0)
        val client = HttpClient(
            MockEngine {
                replayCount.incrementAndGet()
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )
        val secondQueue = DiskQueue(path = queue.path)
        val secondEngine = RetryJournalEngine(secondQueue, deadLetterQueue)

        coroutineScope {
            launch { engine.flush(client) }
            launch { secondEngine.flush(client) }
        }

        assertEquals(3, replayCount.get())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a stored URL that no longer parses is dead-lettered instead of stalling the queue`() = runBlocking {
        queue.enqueue(
            "POST",
            "://not-a-valid-url",
            FrozenHttpHeaders.EMPTY,
            "body".encodeToByteArray(),
        )
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 1, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
        assertEquals(1, deadLetterQueue.peekAll().size)
    }

    @Test
    fun `closeForShutdown rejects new flush calls`() {
        runBlocking {
            val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })
            engine.closeForShutdown()
            assertFailsWith<IllegalStateException> {
                engine.flush(client)
            }
        }
    }

    @Test
    fun `flush refuses a client that has the offline-queue plugin installed`() = runBlocking {
        // Replaying through a client that re-queues its own IOExceptions would duplicate any
        // entry that fails again mid-flush: the plugin re-enqueues it, and since trySend()
        // swallows the resulting OfflineQueuedException, the original is never removed either.
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val selfQueuingClient = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { diskQueue = queue }
        }

        assertFailsWith<IllegalStateException> {
            engine.flush(selfQueuingClient)
        }
        assertEquals(1, queue.peekAll().size)
    }

    @Test
    fun `a 4xx with an unwritable dead-letter queue stops early and keeps the entry`() = runBlocking {
        queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        val readOnlyDlqFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                throw IOException("disk full")
            }
        }
        val dlqStorage = DiskQueue((dir.toString() + "/dead-letter.bin").toPath(), readOnlyDlqFs)
        val failingDlq = DeadLetterQueue(queue, dlqStorage)
        val failingEngine = RetryJournalEngine(queue, failingDlq)
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.BadRequest, headersOf()) })

        val result = failingEngine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(!queue.isEmpty())
        assertEquals(0, failingDlq.size())
    }

    @Test
    fun `flush rethrows cancellation during dead-letter persistence and clears the replay claim`() = runBlocking {
        queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        val dlqPath = (dir.toString() + "/dead-letter.bin").toPath()
        val cancellingDlqFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                if (file == dlqPath) {
                    throw CancellationException("cancelled mid dead-letter")
                }
                return super.appendingSink(file, mustExist)
            }
        }
        val dlqStorage = DiskQueue(dlqPath, cancellingDlqFs)
        val cancellingEngine = RetryJournalEngine(queue, DeadLetterQueue(queue, dlqStorage))
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.BadRequest, headersOf()) })

        assertFailsWith<CancellationException> {
            cancellingEngine.flush(client)
        }

        assertEquals(1, queue.size())
        assertTrue(deadLetterQueue.peekAll().isEmpty())
        val prepared = queue.prepareHeadForReplay()
        assertTrue(prepared is HeadReplayPrepareResult.Ready)
    }

    @Test
    fun `getHeadState reports Blocked when another process holds the replay claim`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val queueB = DiskQueue((dir.toString() + "/main.bin").toPath())
        assertTrue(queueB.prepareHeadForReplay() is com.retryjournal.queue.HeadReplayPrepareResult.Ready)

        assertEquals(QueueHeadState.Blocked, engine.getHeadState())
    }

    @Test
    fun `getHeadState reports Empty on an empty queue`() = runBlocking {
        assertEquals(QueueHeadState.Empty, engine.getHeadState())
    }

    @Test
    fun `flush skips HTTP when a delivery journal marks the head already delivered`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        val httpCalls = AtomicInteger(0)
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
    fun `flush reports persistenceFailed when tombstone write fails after HTTP success`() = runBlocking {
        val mainPath = (dir.toString() + "/persist-fail.bin").toPath()
        val failingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                val delegate = super.appendingSink(file, mustExist)
                return object : ForwardingSink(delegate) {
                    private var flushCount = 0

                    override fun flush() {
                        flushCount++
                        if (file == mainPath && flushCount > 1) {
                            throw IOException("tombstone flush failed")
                        }
                        super.flush()
                    }
                }
            }
        }
        val failingQueue = DiskQueue(mainPath, failingFs)
        val failingEngine = RetryJournalEngine(failingQueue, deadLetterQueue)
        val failingId = failingQueue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = failingEngine.flush(client)

        assertEquals(
            FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true, persistenceFailed = true),
            result,
        )
        assertTrue(!failingQueue.isEmpty())
        assertTrue(
            DeliveryJournal.read(failingFs, mainPath, failingId.sequenceId) !is DeliveryJournalReadResult.Absent,
        )
    }

    @Test
    fun `flush clears a stale replay claim when a delivery journal is pending for the head`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        val otherQueue = DiskQueue(path = queue.path)
        assertTrue(otherQueue.prepareHeadForReplay() is HeadReplayPrepareResult.Ready)

        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })
        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `onProgress can call back into the engine without deadlocking`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client) { progress ->
            when (progress) {
                is FlushProgress.Delivered -> assertEquals(QueueHeadState.Empty, engine.getHeadState())
                is FlushProgress.DeadLettered -> Unit
            }
        }

        assertEquals(1, result.delivered)
    }

    @Test
    fun `getHeadState reports AwaitingLocalRemoval when a delivery journal exists for the head`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )

        val state = engine.getHeadState()

        assertTrue(state is QueueHeadState.AwaitingLocalRemoval)
        assertEquals(id, (state as QueueHeadState.AwaitingLocalRemoval).entry.id)
    }

    @Test
    fun `getHeadState reports AwaitingReplay when a non-head replay claim is active`() = runBlocking {
        val idA = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "https://example.com/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        ReplayClaim.write(
            queue.fileSystem,
            ReplayClaim.claimPath(queue.path),
            idB.sequenceId,
            currentTimeMillis(),
        )

        val state = engine.getHeadState()

        assertTrue(state is QueueHeadState.AwaitingReplay)
        assertEquals(idA, (state as QueueHeadState.AwaitingReplay).entry.id)
    }

    @Test
    fun `flush sends HTTP for head when a journal exists only for a non-head sequence`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "https://example.com/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            idB.sequenceId,
            DeliveryJournal.OUTCOME_DELIVERED,
        )
        val httpCalls = AtomicInteger(0)
        val client = HttpClient(MockEngine {
            httpCalls.incrementAndGet()
            respond("ok", HttpStatusCode.OK, headersOf())
        })

        val result = engine.flush(client)

        assertTrue(httpCalls.get() >= 1)
        assertEquals(FlushResult(delivered = 2, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
        assertTrue(DeliveryJournal.read(queue.fileSystem, queue.path, idB.sequenceId) is DeliveryJournalReadResult.Absent)
    }

    @Test
    fun `invariants hold after a successful flush`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        engine.flush(client)
        queue.assertInvariantsHold()
    }
}
