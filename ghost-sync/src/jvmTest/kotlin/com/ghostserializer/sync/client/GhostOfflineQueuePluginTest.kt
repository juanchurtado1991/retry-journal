package com.ghostserializer.sync.client

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
import kotlinx.coroutines.runBlocking
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
        assertEquals("application/x-ghost", queued?.meta?.headers?.get(HttpHeaders.ContentType))
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
}
