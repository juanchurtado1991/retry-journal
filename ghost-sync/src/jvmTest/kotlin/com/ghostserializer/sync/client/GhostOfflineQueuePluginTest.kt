package com.ghostserializer.sync.client

import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostOfflineQueuePluginTest {

    private lateinit var dir: Path
    private lateinit var diskQueue: DiskQueue

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-client-test").toString().toPath()
        diskQueue = DiskQueue(("$dir/queue.bin").toPath())
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `a connectivity failure is queued and surfaced as OfflineQueuedException`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.post("https://example.com/mutations") { setBody("hello-ghost") }
        }

        val queued = diskQueue.peek()
        assertEquals("POST", queued?.meta?.method)
        assertEquals("https://example.com/mutations", queued?.meta?.url)
        assertEquals("hello-ghost", queued?.body?.decodeToString())
    }

    @Test
    fun `queued Content-Type reflects the body actually sent, not a stale caller-declared one`() = runBlocking {
        // Mirrors what ContentNegotiation does in practice: the caller can declare a Content-Type
        // via contentType(...) that ends up different from what the negotiated OutgoingContent
        // actually carries (e.g. a client with only a Ghost converter registered, called with an
        // explicit but unrelated contentType()). request.headers keeps the caller's stale label;
        // only the body's own contentType is the one the wire — and any replay — should trust.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.post("https://example.com/mutations") {
                contentType(ContentType.Application.Json)
                setBody(ByteArrayContent("ghost-bytes".encodeToByteArray(), ContentType.parse("application/x-ghost")))
            }
        }

        val queued = diskQueue.peek()
        assertEquals("application/x-ghost", queued?.meta?.headers?.findValue(HttpHeaders.ContentType))
    }

    @Test
    fun `a multipart file upload's real bytes are queued, not dropped`() = runBlocking {
        // MultiPartFormDataContent (and any streamed file body) is an
        // OutgoingContent.WriteChannelContent, not a ByteArrayContent — a caller building a
        // typed DTO never hits this path, but a real file/image upload always does.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.post("https://example.com/upload") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("file", "not-actually-empty".encodeToByteArray())
                        },
                    ),
                )
            }
        }

        val queued = diskQueue.peek()
        assertTrue((queued?.body?.size ?: 0) > 0, "expected the multipart body to be captured, got ${queued?.body?.size ?: 0} bytes")
    }

    @Test
    fun `a successful response is not queued`() = runBlocking {
        val client = HttpClient(MockEngine { _ -> respond("ok", HttpStatusCode.OK, headersOf()) }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        client.post("https://example.com/mutations") { setBody("hello-ghost") }

        assertNull(diskQueue.peek())
    }

    @Test
    fun `multi-valued headers are preserved with the record separator`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.post("https://example.com/mutations") {
                headers.append("X-Custom", "value1")
                headers.append("X-Custom", "value2")
            }
        }

        val queued = diskQueue.peek()
        assertEquals(
            "value1${ClientConstants.HEADER_MULTI_VALUE_SEPARATOR}value2",
            queued?.meta?.headers?.findValue("X-Custom"),
        )
    }

    @Test
    fun `body capture failure does not enqueue an empty payload`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        val badBody = object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
            override fun readFrom(): io.ktor.utils.io.ByteReadChannel {
                throw IllegalStateException("Channel already consumed")
            }
        }

        assertFailsWith<BodyCaptureException> {
            client.post("https://example.com/mutations") {
                setBody(badBody)
            }
        }

        assertNull(diskQueue.peek())
    }

    @Test
    fun `concurrent failed requests do not cross-contaminate each other's captured headers`() = runBlocking {
        // Reproduces the race on the plugin's shared header scratch arrays: many requests fail
        // at once on real, separate threads (not just interleaved coroutines on one thread) and
        // each must keep its own header, not one clobbered by a sibling in flight at the same time.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        val concurrency = 50
        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(concurrency) { index ->
                    launch {
                        try {
                            client.post("https://example.com/item-$index") {
                                headers.append("X-Index", index.toString())
                            }
                        } catch (_: OfflineQueuedException) {
                            // Expected — this is the connectivity failure being captured.
                        }
                    }
                }
            }
        }

        val entries = diskQueue.peekAll()
        assertEquals(concurrency, entries.size)
        for (entry in entries) {
            val expectedIndex = entry.meta.url.substringAfterLast("item-")
            assertEquals(
                expectedIndex,
                entry.meta.headers.findValue("X-Index"),
                "entry for ${entry.meta.url} carries a header captured from a different request",
            )
        }
    }

    @Test
    fun `installing plugin without configuring diskQueue throws informative exception`() {
        val exception = assertFailsWith<IllegalStateException> {
            HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) }) {
                install(GhostOfflineQueuePlugin) {
                    // diskQueue is not set
                }
            }
        }
        assertEquals(ClientConstants.PLUGIN_DISK_QUEUE_MISSING, exception.message)
    }
}
