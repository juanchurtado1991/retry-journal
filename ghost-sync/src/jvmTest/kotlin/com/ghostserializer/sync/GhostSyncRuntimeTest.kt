package com.ghostserializer.sync

import com.ghostserializer.sync.GhostSync
import com.ghostserializer.sync.engine.FlushResult
import com.ghostserializer.sync.queue.FrozenHttpHeaders
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GhostSyncRuntimeTest {

    private lateinit var dir: Path
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-runtime-test").toString().toPath()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `flush serializes concurrent callers in the same process`() = runBlocking {
        val activeFlushes = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val ghostSync = GhostSync.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler {
                    val concurrent = activeFlushes.incrementAndGet()
                    maxConcurrent.updateAndGet { current -> maxOf(current, concurrent) }
                    release.await()
                    activeFlushes.decrementAndGet()
                    respond("ok", HttpStatusCode.OK, headersOf())
                }
            }
        }
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        ghostSync.diskQueue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this)

        val first = async { runtime.flush() }
        delay(50)
        val second = async { runtime.flush() }
        delay(50)
        assertEquals(1, maxConcurrent.get())

        release.complete(Unit)
        awaitAll(first, second)

        assertTrue(ghostSync.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `flushWhenOnline returns null while offline`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        assertNull(runtime.flushWhenOnline())
        assertEquals(1, ghostSync.diskQueue.size())

        runtime.shutdown()
    }

    @Test
    fun `flushWhenOnline flushes when the connectivity signal is true`() = runBlocking {
        val connectivity = MutableStateFlow(true)
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        val result = runtime.flushWhenOnline()
        assertEquals(1, result?.delivered)
        assertTrue(ghostSync.diskQueue.isEmpty())

        runtime.shutdown()
    }

    @Test
    fun `auto flush runs when connectivity transitions to online`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this, connectivity)
        runtime.start(autoFlushOnOnline = true)

        connectivity.value = true
        delay(200)

        assertTrue(ghostSync.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `flush rejects calls after shutdown`() = runBlocking {
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        val runtime = GhostSync.createRuntime(ghostSync, this)

        runtime.shutdown()

        assertFailsWith<IllegalStateException> {
            runtime.flush()
        }
        assertTrue(runtime.isShutdown)
    }

    @Test
    fun `createForEngine flushes through a manually wired engine and replay client`() = runBlocking {
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = GhostSyncRuntime.createForEngine(
            engine = ghostSync.engine,
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
    fun `direct ghostSync flush and runtime flush serialize through the same mutex`() = runBlocking {
        val activeFlushes = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val ghostSync = GhostSync.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler {
                    val concurrent = activeFlushes.incrementAndGet()
                    maxConcurrent.updateAndGet { current -> maxOf(current, concurrent) }
                    release.await()
                    activeFlushes.decrementAndGet()
                    respond("ok", HttpStatusCode.OK, headersOf())
                }
            }
        }
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        ghostSync.diskQueue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this)

        val first = async { ghostSync.flush() }
        delay(50)
        val second = async { runtime.flush() }
        delay(50)
        assertEquals(1, maxConcurrent.get())

        release.complete(Unit)
        awaitAll(first, second)

        assertTrue(ghostSync.diskQueue.isEmpty())
        runtime.shutdown()
    }

    @Test
    fun `regression flushWhenOnline observes connectivity transition after start`() = runBlocking {
        val connectivity = MutableStateFlow(false)
        val ghostSync = ghostSyncThatAlwaysSucceeds()
        ghostSync.diskQueue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val runtime = GhostSync.createRuntime(ghostSync, this, connectivity)
        runtime.start(autoFlushOnOnline = false)
        delay(50)

        assertNull(runtime.flushWhenOnline())
        assertEquals(1, ghostSync.diskQueue.size())

        connectivity.value = true
        delay(100)

        assertTrue(runtime.isOnline)
        val result = runtime.flushWhenOnline()
        assertEquals(1, result?.delivered)
        assertTrue(ghostSync.diskQueue.isEmpty())

        runtime.shutdown()
    }

    private fun ghostSyncThatAlwaysSucceeds(): GhostSync =
        GhostSync.create(
            engineFactory = MockEngine,
            queuePath = queuePath(),
        ) {
            engine {
                addHandler { respond("ok", HttpStatusCode.OK, headersOf()) }
            }
        }

    private fun queuePath(): Path = (dir.toString() + "/queue.bin").toPath()
}
