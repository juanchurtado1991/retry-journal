package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.engine.SyncEngineConstants.HEADER_MULTI_VALUE_SEPARATOR
import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpReplayerTest {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private val replayer = HttpReplayer()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-http-replayer-test").toString().toPath()
        queue = DiskQueue((dir.toString() + "/queue.bin").toPath())
    }

    @AfterTest
    fun tearDown() {
        okio.FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `assertSafeToReplayWith rejects a client with the offline queue plugin`() {
        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) }) {
            install(GhostOfflineQueuePlugin) { diskQueue = queue }
        }
        assertFailsWith<IllegalStateException> {
            replayer.assertSafeToReplayWith(client)
        }
    }

    @Test
    fun `send splits multi-valued headers on the replay separator`() = runBlocking {
        val combined = listOf("alpha", "beta").joinToString(HEADER_MULTI_VALUE_SEPARATOR)
        val entry = queue.enqueue(
            "POST",
            "https://example.com/multi",
            FrozenHttpHeaders.of("X-Custom" to combined),
            "body".encodeToByteArray(),
        )
        val peeked = queue.get(entry)!!
        val seen = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                seen.addAll(request.headers.getAll("X-Custom") ?: emptyList())
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )

        val status = replayer.send(client, peeked)

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(listOf("alpha", "beta"), seen)
    }

    @Test
    fun `send returns 400 without hitting the network when the stored URL is invalid`() = runBlocking {
        val entry = queue.enqueue(
            "POST",
            "://bad-url",
            FrozenHttpHeaders.EMPTY,
            ByteArray(0),
        )
        val peeked = queue.get(entry)!!
        var called = false
        val client = HttpClient(
            MockEngine {
                called = true
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )

        assertEquals(HttpStatusCode.BadRequest, replayer.send(client, peeked))
        assertTrue(!called)
    }

    @Test
    fun `send succeeds when stored Content-Type no longer parses`() = runBlocking {
        val entry = queue.enqueue(
            "POST",
            "https://example.com/a",
            FrozenHttpHeaders.of(HttpHeaders.ContentType to "not-a-content-type"),
            "raw".encodeToByteArray(),
        )
        val peeked = queue.get(entry)!!
        val client = HttpClient(
            MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) },
        )

        assertEquals(HttpStatusCode.OK, replayer.send(client, peeked))
    }

    @Test
    fun `send returns the status after draining a large response body`() = runBlocking {
        val entry = queue.enqueue(
            "POST",
            "https://example.com/a",
            FrozenHttpHeaders.EMPTY,
            "body".encodeToByteArray(),
        )
        val peeked = queue.get(entry)!!
        val client = HttpClient(
            MockEngine {
                respond(ByteArray(32 * 1024) { index -> index.toByte() }, HttpStatusCode.OK, headersOf())
            },
        )

        assertEquals(HttpStatusCode.OK, replayer.send(client, peeked))
    }

    @Test
    fun `send does not replay hop-by-hop headers captured with the body`() = runBlocking {
        val entry = queue.enqueue(
            "POST",
            "https://example.com/a",
            FrozenHttpHeaders.of(
                HttpHeaders.TransferEncoding to "chunked",
                HttpHeaders.Host to "wrong.internal.host",
            ),
            "body".encodeToByteArray(),
        )
        val peeked = queue.get(entry)!!
        val client = HttpClient(
            MockEngine { request ->
                assertEquals(null, request.headers[HttpHeaders.TransferEncoding])
                assertEquals(null, request.headers[HttpHeaders.Host])
                respond("ok", HttpStatusCode.OK, headersOf())
            },
        )

        assertEquals(HttpStatusCode.OK, replayer.send(client, peeked))
    }
}
