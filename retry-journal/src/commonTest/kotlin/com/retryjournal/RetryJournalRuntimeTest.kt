package com.retryjournal

import com.retryjournal.freshTestDir
import com.retryjournal.RetryJournal
import com.retryjournal.engine.FlushResult
import com.retryjournal.queue.FrozenHttpHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryJournalRuntimeTest {

    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("retry-journal-runtime-test")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `flush serializes concurrent callers in the same process`() = runBlocking {
        val activeFlushes = TestCounter(0)
        val maxConcurrent = TestCounter(0)
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val retryJournal = RetryJournal.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler {
                    val concurrent = activeFlushes.incrementAndGet()
                    maxConcurrent.updateAndGet { current -> maxOf(current, concurrent) }
                    firstStarted.complete(Unit)
                    release.await()
                    activeFlushes.decrementAndGet()
                    respond("ok", HttpStatusCode.OK, headersOf())
                }
            }
        }
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        retryJournal.diskQueue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this)

        val first = async { runtime.flush() }
        firstStarted.await()
        val second = async { runtime.flush() }
        delay(50)
        assertEquals(1, maxConcurrent.get())

        release.complete(Unit)
        awaitAll(first, second)

        assertTrue(retryJournal.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `flushWhenOnline returns null while offline`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val retryJournal = retryJournalThatAlwaysSucceeds()
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        assertNull(runtime.flushWhenOnline())
        assertEquals(1, retryJournal.diskQueue.size())

        runtime.shutdown()
    }

    @Test
    fun `flushWhenOnline flushes when the connectivity signal is true`() = runBlocking {
        val connectivity = MutableStateFlow(true)
        val retryJournal = retryJournalThatAlwaysSucceeds()
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        val result = runtime.flushWhenOnline()
        assertEquals(1, result?.delivered)
        assertTrue(retryJournal.diskQueue.isEmpty())

        runtime.shutdown()
    }

    @Test
    fun `auto flush runs when connectivity transitions to online`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val retryJournal = retryJournalThatAlwaysSucceeds()
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this, connectivity)
        runtime.start(autoFlushOnOnline = true)

        connectivity.value = true
        delay(200)

        assertTrue(retryJournal.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `flush rejects calls after shutdown`() = runBlocking {
        val retryJournal = retryJournalThatAlwaysSucceeds()
        val runtime = RetryJournal.createRuntime(retryJournal, this)

        runtime.shutdown()

        assertFailsWith<IllegalStateException> {
            runtime.flush()
        }
        assertTrue(runtime.isShutdown)
    }

    @Test
    fun `createForEngine flushes through a manually wired engine and replay client`() = runBlocking {
        val retryJournal = retryJournalThatAlwaysSucceeds()
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = RetryJournalRuntime.createForEngine(
            engine = retryJournal.engine,
            replayClient = HttpClient(MockEngine) {
                engine {
                    addHandler { respond("ok", HttpStatusCode.OK, headersOf()) }
                }
            },
            scope = this,
        )

        val result = runtime.flush()
        assertEquals(1, result.delivered)
        runtime.shutdown()
    }

    @Test
    fun `direct retryJournal flush and runtime flush serialize through the same mutex`() = runBlocking {
        val activeFlushes = TestCounter(0)
        val maxConcurrent = TestCounter(0)
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val retryJournal = RetryJournal.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler {
                    val concurrent = activeFlushes.incrementAndGet()
                    maxConcurrent.updateAndGet { current -> maxOf(current, concurrent) }
                    firstStarted.complete(Unit)
                    release.await()
                    activeFlushes.decrementAndGet()
                    respond("ok", HttpStatusCode.OK, headersOf())
                }
            }
        }
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        retryJournal.diskQueue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this)

        val first = async { retryJournal.flush() }
        firstStarted.await()
        val second = async { runtime.flush() }
        delay(50)
        assertEquals(1, maxConcurrent.get())

        release.complete(Unit)
        awaitAll(first, second)

        assertTrue(retryJournal.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `regression flushWhenOnline observes connectivity transition after start`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val retryJournal = retryJournalThatAlwaysSucceeds()
        retryJournal.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = RetryJournal.createRuntime(retryJournal, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        assertNull(runtime.flushWhenOnline())
        assertEquals(1, retryJournal.diskQueue.size())

        connectivity.value = true
        delay(100)

        assertTrue(runtime.isOnline)
        val result = runtime.flushWhenOnline()
        assertEquals(1, result?.delivered)
        assertTrue(retryJournal.diskQueue.isEmpty())

        runtime.shutdown()
    }

    @Test
    fun `shutdown can be retried after a failed close instead of leaking resources forever`() = runBlocking {
        // Regression: shutdown used to mark itself done *before* running the close action. A
        // close failure (a replay still in flight, say) left it permanently poisoned — shutdown
        // was already true, so every later call was a silent no-op that never actually closed
        // anything.
        var attempts = 0
        val runtime = RetryJournalRuntime.createForEngine(
            engine = retryJournalThatAlwaysSucceeds().engine,
            replayClient = HttpClient(MockEngine) {
                engine { addHandler { respond("ok", HttpStatusCode.OK, headersOf()) } }
            },
            scope = this,
            onShutdown = {
                attempts++
                if (attempts == 1) {
                    error("simulated close failure")
                }
            },
        )

        assertFailsWith<IllegalStateException> { runtime.shutdown() }
        assertTrue(!runtime.isShutdown, "a failed close must not poison the runtime")

        runtime.shutdown() // retried — succeeds this time
        assertTrue(runtime.isShutdown)
        assertEquals(2, attempts)
    }

    private fun retryJournalThatAlwaysSucceeds(): RetryJournal =
        RetryJournal.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler { respond("ok", HttpStatusCode.OK, headersOf()) }
            }
        }

    private fun queuePath(): Path = (dir.toString() + "/queue.bin").toPath()
}
