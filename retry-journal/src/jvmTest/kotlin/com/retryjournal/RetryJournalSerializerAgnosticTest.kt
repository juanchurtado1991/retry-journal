package com.retryjournal

import com.retryjournal.client.OfflineQueuedException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class KserPayload(val name: String, val count: Int)

/**
 * Proves the claim in [RetryJournal]'s KDoc: the *payload* serializer is a caller concern, entirely
 * separate from the Ghost-backed queue record. This client is configured with
 * kotlinx.serialization's own `json()` content negotiation — not `ghost()` — for its request
 * bodies, yet RetryJournalOfflineQueuePlugin/RetryJournalEngine/RetryJournal all work exactly the same as with
 * any other payload serializer.
 */
class RetryJournalSerializerAgnosticTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("retry-journal-agnostic-test").toString().toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `offline queueing and flush both work with kotlinx-serialization content negotiation`() = runBlocking {
        var reachable = false

        val retryJournal = RetryJournal.create(
            engineFactory = MockEngine,
            queuePath = (dir.toString() + "/queue.bin").toPath(),
        ) {
            engine {
                addHandler { request ->
                    if (reachable) {
                        val echoedBytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
                        respond(echoedBytes, HttpStatusCode.OK, headersOf())
                    } else {
                        throw IOException("offline")
                    }
                }
            }
            install(ContentNegotiation) { json() }
        }

        try {
            assertFailsWith<OfflineQueuedException> {
                retryJournal.client.post("https://example.com/kser") {
                    contentType(ContentType.Application.Json)
                    setBody(KserPayload(name = "hi", count = 1))
                }
            }
            assertEquals(1, retryJournal.diskQueue.size())

            reachable = true
            val result = retryJournal.flush()

            assertEquals(1, result.delivered)
            assertTrue(retryJournal.diskQueue.isEmpty())
        } finally {
            retryJournal.close()
        }
    }
}
