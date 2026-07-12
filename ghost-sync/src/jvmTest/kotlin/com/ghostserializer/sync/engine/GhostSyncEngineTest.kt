package com.ghostserializer.sync.engine

import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GhostSyncEngineTest {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: GhostSyncEngine

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-engine-test").toString().toPath()
        queue = DiskQueue((dir.toString() + "/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dead-letter.bin").toPath()))
        engine = GhostSyncEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `a 2xx response removes the entry from the queue and counts as delivered`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", emptyMap(), "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 1, deadLettered = 0, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a 4xx response moves the entry to the dead-letter queue and keeps flushing`() = runBlocking {
        queue.enqueue("POST", "https://example.com/bad", emptyMap(), "bad".encodeToByteArray())
        queue.enqueue("POST", "https://example.com/good", emptyMap(), "good".encodeToByteArray())
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
        val id = queue.enqueue("POST", "https://example.com/a", emptyMap(), "a".encodeToByteArray())
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertEquals(id, queue.peek()?.id)
    }

    @Test
    fun `a network failure stops the loop without touching the dead-letter queue`() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", emptyMap(), "a".encodeToByteArray())
        val client = HttpClient(MockEngine { throw IOException("connection reset") })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = true), result)
        assertTrue(deadLetterQueue.peekAll().isEmpty())
        assertTrue(!queue.isEmpty())
    }

    @Test
    fun `an empty queue flushes instantly with no work done`() = runBlocking {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })

        val result = engine.flush(client)

        assertEquals(FlushResult(delivered = 0, deadLettered = 0, stoppedEarly = false), result)
    }
}
