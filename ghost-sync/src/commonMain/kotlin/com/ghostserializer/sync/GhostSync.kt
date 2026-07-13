package com.ghostserializer.sync

import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.FlushResult
import com.ghostserializer.sync.engine.GhostSyncEngine
import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.utils.io.core.Closeable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The plug-and-play entry point: one call wires [DiskQueue] + [DeadLetterQueue] + [GhostSyncEngine]
 * and installs [GhostOfflineQueuePlugin] on a ready-to-use [client]. Every piece it builds stays
 * public ([diskQueue], [deadLetterQueue], [engine], [client]) — this is a convenience default, not
 * a replacement for wiring the pieces by hand when you need something this constructor doesn't
 * expose.
 *
 * The queue's own on-disk bookkeeping record (method/url/headers) always uses Ghost — it is the
 * single hottest path in this library (every `enqueue()`/`peek()`/compaction touches it) and
 * `:ghost-sync` already depends on Ghost, so there is no reason not to.
 *
 * *Your* request/response payloads are a separate, unrelated concern: nothing here, in
 * [GhostOfflineQueuePlugin], or in [GhostSyncEngine] cares what produced a request body — the
 * plugin captures whatever [io.ktor.http.content.OutgoingContent.ByteArrayContent] bytes already
 * exist at send time and the engine replays those same raw bytes. Configure whatever
 * `ContentNegotiation` you want for those — Ghost's `ghost()`, kotlinx.serialization's `json()`,
 * both together, or none at all — via [httpClientConfig]; this class installs no
 * content-negotiation converter of its own.
 */
class GhostSync private constructor(
    val diskQueue: DiskQueue,
    val deadLetterQueue: DeadLetterQueue,
    val engine: GhostSyncEngine,
    val client: HttpClient,
    private val replayClient: HttpClient,
) : Closeable {

    /** Delegates to [GhostSyncEngine.flush] using a private client that never risks the
     * duplicate-enqueue footgun documented there — you never have to think about it. */
    suspend fun flush(): FlushResult = engine.flush(replayClient)

    override fun close() {
        client.close()
        replayClient.close()
    }

    companion object {
        fun <T : HttpClientEngineConfig> create(
            engineFactory: HttpClientEngineFactory<T>,
            queuePath: Path,
            deadLetterPath: Path = defaultDeadLetterPath(queuePath),
            fileSystem: FileSystem = FileSystem.SYSTEM,
            httpClientConfig: HttpClientConfig<T>.() -> Unit = {},
        ): GhostSync {
            val queue = DiskQueue(queuePath, fileSystem)
            val deadLetters = DeadLetterQueue(mainQueue = queue, storage = DiskQueue(deadLetterPath, fileSystem))
            val engine = GhostSyncEngine(queue, deadLetters)

            val client = HttpClient(engineFactory) {
                httpClientConfig()
                install(GhostOfflineQueuePlugin) { diskQueue = queue }
            }
            val replayClient = HttpClient(engineFactory) {
                httpClientConfig()
            }

            return GhostSync(queue, deadLetters, engine, client, replayClient)
        }

        private fun defaultDeadLetterPath(queuePath: Path): Path =
            (queuePath.toString() + GhostSyncConstants.DEFAULT_DEAD_LETTER_PATH_SUFFIX).toPath()
    }
}
