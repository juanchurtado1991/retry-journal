package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GhostOfflineQueuePluginTest {

    private lateinit var dir: Path
    private lateinit var diskQueue: DiskQueue

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-client-test").toString().toPath()
        diskQueue = DiskQueue((dir.toString() + "/queue.bin").toPath())
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
    fun `a successful response is not queued`() = runBlocking {
        val client = HttpClient(MockEngine { request -> respond("ok", HttpStatusCode.OK, headersOf()) }) {
            install(GhostOfflineQueuePlugin) { this.diskQueue = this@GhostOfflineQueuePluginTest.diskQueue }
        }

        client.post("https://example.com/mutations") { setBody("hello-ghost") }

        assertNull(diskQueue.peek())
    }
}
