package com.retryjournal.queue

import com.retryjournal.deadletter.DeadLetterQueue
import com.retryjournal.engine.RetryJournalEngine
import com.retryjournal.freshTestDir
import com.retryjournal.queue.disk.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class QueueInvariantsTest {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var engine: RetryJournalEngine

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("queue-invariants-test")
        queue = DiskQueue(dir.resolve("main.bin"))
        engine = RetryJournalEngine(queue, DeadLetterQueue(queue, DiskQueue(dir.resolve("dlq.bin"))))
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `assertInvariantsHold passes on an empty queue`() = runBlocking {
        queue.assertInvariantsHold()
    }

    @Test
    fun `assertInvariantsHold passes after enqueue and flush`() = runBlocking {
        repeat(5) { index ->
            queue.enqueue("POST", "https://example.com/$index", FrozenHttpHeaders.EMPTY, "body-$index".encodeToByteArray())
        }
        queue.assertInvariantsHold()

        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })
        engine.flush(client)
        queue.assertInvariantsHold()
    }

    @Test
    fun `assertInvariantsHold passes after randomized enqueue and partial flush`() = runBlocking {
        val rng = Random(42)
        val client = HttpClient(MockEngine { call ->
            val path = call.url.encodedPath
            when {
                path.endsWith("/bad") -> respond("", HttpStatusCode.BadRequest, headersOf())
                path.endsWith("/offline") -> throw IOException("offline")
                else -> respond("ok", HttpStatusCode.OK, headersOf())
            }
        })

        repeat(30) { step ->
            when (rng.nextInt(3)) {
                0 -> queue.enqueue(
                    "POST",
                    "https://example.com/a-$step",
                    FrozenHttpHeaders.EMPTY,
                    "a".encodeToByteArray(),
                )
                1 -> queue.enqueue(
                    "POST",
                    "https://example.com/bad",
                    FrozenHttpHeaders.EMPTY,
                    "bad".encodeToByteArray(),
                )
                2 -> queue.enqueue(
                    "POST",
                    "https://example.com/offline",
                    FrozenHttpHeaders.EMPTY,
                    "offline".encodeToByteArray(),
                )
            }
            engine.flush(client)
            queue.assertInvariantsHold()
        }
    }
}
