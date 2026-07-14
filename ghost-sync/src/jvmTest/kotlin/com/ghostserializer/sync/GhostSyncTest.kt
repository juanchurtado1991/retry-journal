package com.ghostserializer.sync

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertFailsWith

/** Wraps a real [MockEngine] but fails [close] — reproduces a real [HttpClientEngine] closing
 * uncleanly (e.g. socket teardown throwing), to prove [GhostSync.close] still releases its other
 * resources instead of abandoning them behind the first failure. */
private class ThrowingCloseEngine(private val delegate: HttpClientEngine) : HttpClientEngine by delegate {
    override fun close() {
        delegate.close()
        throw IOException("simulated engine close failure")
    }
}

private object ThrowingCloseEngineFactory : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine =
        ThrowingCloseEngine(MockEngine.create(block))
}

class GhostSyncTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ghost-sync-facade-test").toString().toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `close still releases the disk queue and dead-letter queue when the client's engine fails to close`(): Unit = runBlocking {
        val ghostSync = GhostSync.create(
            engineFactory = ThrowingCloseEngineFactory,
            queuePath = (dir.toString() + "/queue.bin").toPath(),
        ) {
            engine {
                addHandler { respond("ok", HttpStatusCode.OK, headersOf()) }
            }
        }

        // The engine's close() failure surfaces wrapped in a coroutine completion-handler
        // exception rather than as the plain IOException thrown — an artifact of
        // HttpClientEngine's own Job-based lifecycle, not of GhostSync.close(). What matters here
        // is only that *something* propagates, and that it doesn't stop the rest of close() from
        // running.
        assertFailsWith<Throwable> { ghostSync.close() }

        // client.close() threw first; if close() gave up right there, diskQueue and
        // deadLetterQueue would never get a chance to run. Both rejecting further operations
        // (their own "closed" guard) proves close() kept going past that failure instead of
        // leaking their file handles.
        assertFailsWith<IllegalStateException> { ghostSync.diskQueue.peek() }
        assertFailsWith<IllegalStateException> { ghostSync.deadLetterQueue.size() }
    }
}
