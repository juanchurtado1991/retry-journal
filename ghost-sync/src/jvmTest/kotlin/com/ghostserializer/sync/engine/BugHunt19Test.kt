package com.ghostserializer.sync.engine

import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.HeadReplayPrepareResult
import com.ghostserializer.sync.queue.disk.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Regression tests from bug hunt round 19. */
class BugHunt19Test {

    private lateinit var dir: Path
    private lateinit var queue: DiskQueue
    private lateinit var deadLetterQueue: DeadLetterQueue
    private lateinit var engine: GhostSyncEngine

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bug-hunt-19").toString().toPath()
        queue = DiskQueue((dir.toString() + "/main.bin").toPath())
        deadLetterQueue = DeadLetterQueue(queue, DiskQueue((dir.toString() + "/dlq.bin").toPath()))
        engine = GhostSyncEngine(queue, deadLetterQueue)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    /** [HeadReplayExecutor] used to call the suspend [DiskQueue.abortHeadReplayClaim] from inside
     * `catch (e: CancellationException)` handlers. A suspend call made after a coroutine's own job
     * is already cancelled throws immediately without running its body, so the claim was never
     * actually cleared — the head stayed blocked until [com.ghostserializer.sync.queue.disk.DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS]
     * elapsed instead of being released right away. */
    @Test
    fun cancellingFlushMidHttpCallReleasesReplayClaimImmediately() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val callStarted = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            callStarted.complete(Unit)
            awaitCancellation()
        })

        val flushJob = launch { engine.flush(client) }
        callStarted.await()
        flushJob.cancelAndJoin()

        assertTrue(
            queue.prepareHeadForReplay() is HeadReplayPrepareResult.Ready,
            "replay claim should be released immediately on cancellation, not left blocking the head",
        )
    }

    @Test
    fun flushStillSucceedsNormallyAfterACancelledAttempt() = runBlocking {
        queue.enqueue("POST", "https://example.com/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val callStarted = CompletableDeferred<Unit>()
        val hangingClient = HttpClient(MockEngine {
            callStarted.complete(Unit)
            awaitCancellation()
        })
        val flushJob = launch { engine.flush(hangingClient) }
        callStarted.await()
        flushJob.cancelAndJoin()

        val client = HttpClient(MockEngine { respond("ok", HttpStatusCode.OK, headersOf()) })
        val result = engine.flush(client)

        assertTrue(result.delivered == 1 && !result.stoppedEarly)
        assertTrue(queue.isEmpty())
    }
}
