package com.ghostserializer.sync.engine

import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GhostSyncEngineTest {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: GhostSyncEngine

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-engine-test").toString().toPath()
        queue = DiskQueue(("$dir/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))
        engine = GhostSyncEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
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
    fun `getEntry and getStatus let a caller drive its own step-by-step loop`() = runBlocking {
        val id = queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val entry = engine.getEntry()
        assertEquals(id, entry?.id)

        // getStatus alone has none of flush()'s side effects — the entry is still on the queue.
        val status = engine.getStatus(client, entry!!)
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(id, queue.peek()?.id)

        // The caller owns applying the outcome; queue.remove(...) here mirrors what flush() would
        // have done automatically for a 2xx.
        queue.remove(entry.id)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `getEntry returns null once the queue is drained`() = runBlocking {
        assertEquals(null, engine.getEntry())
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
                throw IllegalArgumentException("Malformed URL or body")
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
    fun `flush refuses a client that has the offline-queue plugin installed`() = runBlocking {
        // Replaying through a client that re-queues its own IOExceptions would duplicate any
        // entry that fails again mid-flush: the plugin re-enqueues it, and since trySend()
        // swallows the resulting OfflineQueuedException, the original is never removed either.
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val selfQueuingClient = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { diskQueue = queue }
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
        val failingEngine = GhostSyncEngine(queue, failingDlq)
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.BadRequest, headersOf()) })

        val result = failingEngine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(!queue.isEmpty())
        assertEquals(0, failingDlq.size())
    }
}
