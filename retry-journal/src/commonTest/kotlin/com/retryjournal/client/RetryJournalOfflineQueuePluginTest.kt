package com.retryjournal.client

import com.retryjournal.freshTestDir
import com.retryjournal.peekAll
import com.retryjournal.queue.disk.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryJournalOfflineQueuePluginTest {

    private lateinit var dir: Path
    private lateinit var diskQueue: DiskQueue

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("retry-journal-client-test")
        diskQueue = DiskQueue(("$dir/queue.bin").toPath())
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `a connectivity failure is queued and surfaced as OfflineQueuedException`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
    fun `all headers survive capture when a request has more than the scratch array's initial capacity`() =
        runBlocking {
            // Regression: RequestCapture's header scratch arrays grow by replacing themselves
            // with a blank array instead of copying the old one forward, silently wiping every
            // header captured before the growth point. HEADER_SCRATCH_INITIAL_CAPACITY is 8, so
            // this needs strictly more than 8 custom headers (plus Content-Type/Host, etc. Ktor
            // adds its own) to actually cross that boundary.
            val client = HttpClient(MockEngine { throw IOException("no network") }) {
                install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
            }
            val headerCount = 20

            assertFailsWith<OfflineQueuedException> {
                client.post("https://example.com/mutations") {
                    for (index in 0 until headerCount) {
                        header("X-Custom-$index", "value-$index")
                    }
                    setBody("body")
                }
            }

            val queued = diskQueue.peek()
            assertTrue(queued != null)
            for (index in 0 until headerCount) {
                assertEquals(
                    "value-$index",
                    queued.meta.headers.findValue("X-Custom-$index"),
                    "header X-Custom-$index was lost or corrupted during capture",
                )
            }
        }

    @Test
    fun `queued Content-Type reflects the body actually sent not a stale caller-declared one`() = runBlocking {
        // Mirrors what ContentNegotiation does in practice: the caller can declare a Content-Type
        // via contentType(...) that ends up different from what the negotiated OutgoingContent
        // actually carries (e.g. a client with only a Ghost converter registered, called with an
        // explicit but unrelated contentType()). request.headers keeps the caller's stale label;
        // only the body's own contentType is the one the wire — and any replay — should trust.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
    fun `a multipart file upload's real bytes are queued not dropped`() = runBlocking {
        // MultiPartFormDataContent (and any streamed file body) is an
        // OutgoingContent.WriteChannelContent, not a ByteArrayContent — a caller building a
        // typed DTO never hits this path, but a real file/image upload always does.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        client.post("https://example.com/mutations") { setBody("hello-ghost") }

        assertNull(diskQueue.peek())
    }

    @Test
    fun `multi-valued headers are preserved with the record separator`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
    fun `a wrapped IOException still queues the request for later delivery`() = runBlocking {
        val client = HttpClient(MockEngine { throw RuntimeException(IOException("no network")) }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
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
    fun `a body larger than maxRecordFieldSize fails closed instead of enqueueing`() = runBlocking {
        val smallQueue = DiskQueue(
            ("$dir/small-queue.bin").toPath(),
            maxRecordFieldSize = 64,
        )
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { diskQueue = smallQueue }
        }

        assertFailsWith<BodyCaptureException> {
            client.post("https://example.com/upload") {
                setBody(ByteArray(128))
            }
        }
        assertTrue(smallQueue.isEmpty())
    }

    @Test
    fun `a disk enqueue failure is not surfaced as OfflineQueuedException`() = runBlocking {
        val failingQueue = DiskQueue(
            ("$dir/failing-queue.bin").toPath(),
            object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun appendingSink(file: Path, mustExist: Boolean): okio.Sink {
                    throw okio.IOException("disk full")
                }
            },
        )
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { diskQueue = failingQueue }
        }

        val error = assertFailsWith<okio.IOException> {
            client.post("https://example.com/mutations") { setBody("hello-ghost") }
        }

        assertEquals("disk full", error.message)
        assertTrue(failingQueue.isEmpty())
    }

    @Test
    fun `cancelling capture mid body-write surfaces as CancellationException not BodyCaptureException`() = runBlocking {
        // Regression: captureWriteChannelBody/captureReadChannelBody used to catch Throwable
        // without excluding CancellationException, wrapping a genuine coroutine cancellation
        // (the caller's scope tearing down mid-capture, a withTimeout upstream, etc.) as a
        // business-logic BodyCaptureException instead of letting it propagate and cancel normally.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }
        val captureStarted = CompletableDeferred<Unit>()
        val hangingBody = object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                captureStarted.complete(Unit)
                awaitCancellation()
            }
        }

        var observed: Throwable? = null
        val job = launch {
            try {
                client.post("https://example.com/mutations") { setBody(hangingBody) }
            } catch (e: Throwable) {
                observed = e
            }
        }
        captureStarted.await()
        job.cancelAndJoin()

        assertTrue(observed is CancellationException, "expected CancellationException, got $observed")
        assertNull(diskQueue.peek(), "a cancelled capture must not enqueue a partial entry")
    }

    @Test
    fun `a slow body capture does not block a concurrent request's header capture`() = runBlocking {
        // Regression: capture() used to hold its mutex for the whole call, including the body
        // read — but only captureHeaders touches the shared scratch arrays the mutex protects.
        // A slow multipart/streaming body from one failing request used to block every other
        // concurrently failing request's header capture for no reason.
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }
        val slowBodyStarted = CompletableDeferred<Unit>()
        val releaseSlowBody = CompletableDeferred<Unit>()
        val slowBody = object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) {
                slowBodyStarted.complete(Unit)
                releaseSlowBody.await()
            }
        }

        val slowJob = launch {
            try {
                client.post("https://example.com/slow") { setBody(slowBody) }
            } catch (_: OfflineQueuedException) {
                // Expected once releaseSlowBody completes below.
            }
        }
        slowBodyStarted.await()

        // While the slow request's body capture is still blocked, a concurrent header-only
        // request must still complete quickly instead of queueing up behind it.
        withTimeout(5_000) {
            assertFailsWith<OfflineQueuedException> {
                client.post("https://example.com/fast") { header("X-Fast", "1") }
            }
        }

        releaseSlowBody.complete(Unit)
        slowJob.join()
    }

    @Test
    fun `a GET is not queued by default when it fails offline`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<IOException> {
            client.get("https://example.com/profile")
        }

        assertNull(diskQueue.peek(), "GET has no caller left waiting for a delayed response — must not be queued")
    }

    @Test
    fun `a HEAD and OPTIONS are not queued by default when they fail offline`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<IOException> { client.head("https://example.com/profile") }
        assertFailsWith<IOException> { client.options("https://example.com/profile") }

        assertTrue(diskQueue.isEmpty())
    }

    @Test
    fun `the enqueue-override header forces a GET to be queued and is stripped before persisting`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.get("https://example.com/mark-read") {
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "true")
            }
        }

        val queued = diskQueue.peek()
        assertEquals("https://example.com/mark-read", queued?.meta?.url)
        assertNull(
            queued?.meta?.headers?.findValue(RetryJournalHeaders.ENQUEUE_OVERRIDE),
            "the override header must never be persisted",
        )
    }

    @Test
    fun `the enqueue-override header forces a POST to be skipped and the original exception surfaces`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<IOException> {
            client.post("https://example.com/analytics-ping") {
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "false")
            }
        }

        assertTrue(diskQueue.isEmpty())
    }

    @Test
    fun `the enqueue-override header never reaches the wire`() = runBlocking {
        var sentHeaderValue: String? = null
        val client = HttpClient(MockEngine { request ->
            sentHeaderValue = request.headers[RetryJournalHeaders.ENQUEUE_OVERRIDE]
            throw IOException("no network")
        }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<OfflineQueuedException> {
            client.get("https://example.com/mark-read") {
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "true")
            }
        }

        assertNull(sentHeaderValue, "the override header must be stripped before the request is sent")
    }

    @Test
    fun `an unparseable enqueue-override header value falls back to the default rule`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<IOException> {
            client.get("https://example.com/profile") {
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "yes-please")
            }
        }

        assertTrue(diskQueue.isEmpty())
    }

    @Test
    fun `a custom shouldEnqueue predicate replaces the default method-based rule`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) {
                this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue
                shouldEnqueue = { request -> "/orders" in request.url.buildString() }
            }
        }

        assertFailsWith<OfflineQueuedException> {
            client.get("https://example.com/orders/42") // GET, but matches the custom predicate
        }
        assertFailsWith<IOException> {
            client.post("https://example.com/analytics-ping") // POST, but doesn't match it
        }

        assertEquals(1, diskQueue.size())
        assertEquals("https://example.com/orders/42", diskQueue.peek()?.meta?.url)
    }

    @Test
    fun `a throwing shouldEnqueue predicate does not block the request from being sent`() = runBlocking {
        var handlerCalls = 0
        val client = HttpClient(MockEngine {
            handlerCalls++
            respond("ok", HttpStatusCode.OK, headersOf())
        }) {
            install(RetryJournalOfflineQueuePlugin) {
                this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue
                shouldEnqueue = { error("boom") }
            }
        }

        client.post("https://example.com/orders")

        assertEquals(1, handlerCalls, "the request must still be attempted even though the predicate threw")
    }

    @Test
    fun `a throwing shouldEnqueue predicate falls back to the default rule on a connectivity failure`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) {
                this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue
                shouldEnqueue = { error("boom") }
            }
        }

        // POST matches the default rule even though the custom predicate blew up.
        assertFailsWith<OfflineQueuedException> {
            client.post("https://example.com/orders")
        }
        assertEquals(1, diskQueue.size())
    }

    @Test
    fun `when the enqueue-override header is set twice the last value wins`() = runBlocking {
        val client = HttpClient(MockEngine { throw IOException("no network") }) {
            install(RetryJournalOfflineQueuePlugin) { this.diskQueue = this@RetryJournalOfflineQueuePluginTest.diskQueue }
        }

        assertFailsWith<IOException> {
            client.get("https://example.com/mark-read") {
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "true")
                header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "false") // must win over the first value
            }
        }

        assertTrue(diskQueue.isEmpty())
    }

    @Test
    fun `installing plugin without configuring diskQueue throws informative exception`() {
        val exception = assertFailsWith<IllegalStateException> {
            HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) }) {
                install(RetryJournalOfflineQueuePlugin) {
                    // diskQueue is not set
                }
            }
        }
        assertEquals(ClientConstants.PLUGIN_DISK_QUEUE_MISSING, exception.message)
    }
}
