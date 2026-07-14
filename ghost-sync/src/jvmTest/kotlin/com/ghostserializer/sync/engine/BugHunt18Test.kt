package com.ghostserializer.sync.engine

import com.ghostserializer.sync.GhostSync
import com.ghostserializer.sync.GhostSyncRuntime
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.DeliveryJournal
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.disk.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression tests from bug hunt round 18. */
class BugHunt18Test {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: GhostSyncEngine

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bug-hunt-18").toString().toPath()
        queue = DiskQueue((dir.toString() + "/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dlq.bin").toPath()))
        engine = GhostSyncEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun flushDeadLetterJournalRecoverySkipsRecordWhenDlqAlreadyHasEntry() {
        runBlocking {
        val id = queue.enqueue("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        deadLetterQueue.record("POST", "https://example.com/bad", FrozenHttpHeaders.EMPTY, "bad".encodeToByteArray())
        DeliveryJournal.write(
            queue.fileSystem,
            queue.path,
            id.sequenceId,
            DeliveryJournal.OUTCOME_DEAD_LETTERED,
        )
        val httpCalls = AtomicInteger(0)
        val client = HttpClient(MockEngine {
            httpCalls.incrementAndGet()
            respond("ok", HttpStatusCode.OK, headersOf())
        })

        val result = engine.flush(client)

        assertEquals(0, httpCalls.get())
        assertEquals(1, deadLetterQueue.size())
        assertEquals(FlushResult(delivered = 0, deadLettered = 1, stoppedEarly = false), result)
        assertTrue(queue.isEmpty())
        }
    }

    @Test
    fun runtimeGetHeadStateRejectsCallsAfterShutdown() {
        runBlocking {
        val ghostSync = GhostSync.create(
            engineFactory = MockEngine,
            queuePath = (dir.toString() + "/runtime.bin").toPath(),
        ) {
            engine { addHandler { respond("ok", HttpStatusCode.OK, headersOf()) } }
        }
        val runtime = GhostSync.createRuntime(ghostSync, this)
        runtime.shutdown()

        assertFailsWith<IllegalStateException> { runtime.getHeadState() }
        }
    }
}
