package com.ghostserializer.sync

import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.FlushProgress
import com.ghostserializer.sync.engine.FlushResult
import com.ghostserializer.sync.engine.GhostSyncEngine
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.DiskQueueConstants
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.plugin
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import com.ghostserializer.sync.queue.platform.systemFileSystem

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
 * plugin captures whatever bytes exist at send time, whether that's an in-memory
 * [io.ktor.http.content.OutgoingContent.ByteArrayContent] (a serialized DTO) or a streamed
 * [io.ktor.http.content.OutgoingContent.WriteChannelContent]/[io.ktor.http.content.OutgoingContent.ReadChannelContent]
 * (a file/image upload, e.g. `MultiPartFormDataContent`) — and the engine replays those same raw
 * bytes. Configure whatever `ContentNegotiation` you want for those — Ghost's `ghost()`,
 * kotlinx. Serialization's `json()`, both together, or none at all — via httpClientConfig; this
 * class installs no content-negotiation converter of its own — see [create]'s `maxRecordFieldSize`
 * for the cap on how large a single queued body can be.
 */
class GhostSync private constructor(
    val diskQueue: DiskQueue,
    val deadLetterQueue: DeadLetterQueue,
    val engine: GhostSyncEngine,
    val client: HttpClient,
    private val replayClient: HttpClient,
    private val offlineQueuePlugin: GhostOfflineQueuePlugin,
) : Closeable {

    /** Delegates to [GhostSyncEngine.flush] using a private client that never risks the
     * duplicate-enqueue footgun documented there — you never have to think about it. [onProgress]
     * is the same per-entry callback [GhostSyncEngine.flush] takes, for a caller that wants to
     * show the queue draining in real time. */
    suspend fun flush(
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult = engine.flush(replayClient, onProgress)

    /** Closes every owned resource even if an earlier one throws while closing — a failure
     * closing [client] (the underlying engine tearing down sockets, say) must not leak
     * [replayClient]'s connections or [diskQueue]/[deadLetterQueue]'s open file handles.
     *
     * Throws [IllegalStateException] instead of proceeding if a replay session or a live
     * [client] request is still in flight. [LifecycleGate][com.ghostserializer.sync.queue.LifecycleGate]
     * serializes each check against new work starting — there is no TOCTOU window.
     *
     * [diskQueue] and [deadLetterQueue]'s own `close()` reject new operations the same way. */
    override fun close() {
        shutdownLifecycle()
        closeOwnedResources()
    }

    private fun shutdownLifecycle() {
        engine.closeForShutdown()
        offlineQueuePlugin.closeForShutdown()
        deadLetterQueue.closeForShutdown()
    }

    private fun closeOwnedResources() {
        try {
            client.close()
        } finally {
            try {
                replayClient.close()
            } finally {
                try {
                    diskQueue.close()
                } finally {
                    deadLetterQueue.close()
                }
            }
        }
    }

    companion object {
        /**
         * @param maxRecordFieldSize Caps a single queued meta or body field; `enqueue()` throws
         * [com.ghostserializer.sync.queue.record.RecordTooLargeException] past this rather than writing
         * something it could never read back. Raise it if your uploads are routinely bigger than
         * the default (64 MiB) — lowering it doesn't reduce memory use for anything already under
         * the limit (`enqueue()` only ever allocates exactly what you pass it), it just rejects
         * oversized bodies earlier and shrinks the worst-case allocation a corrupted length field
         * could trigger on read.
         */
        fun <T : HttpClientEngineConfig> create(
            engineFactory: HttpClientEngineFactory<T>,
            queuePath: Path,
            deadLetterPath: Path = defaultDeadLetterPath(queuePath),
            fileSystem: FileSystem? = null,
            maxRecordFieldSize: Int = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            httpClientConfig: HttpClientConfig<T>.() -> Unit = {},
        ): GhostSync {
            val resolvedFileSystem = fileSystem ?: systemFileSystem()
            val (queue, deadLetters, engine) = createStorageAndEngine(
                queuePath,
                deadLetterPath,
                resolvedFileSystem,
                maxRecordFieldSize,
            )
            val (client, replayClient, offlineQueuePlugin) = createClients(
                engineFactory,
                queue,
                httpClientConfig,
            )
            return GhostSync(
                queue,
                deadLetters,
                engine,
                client,
                replayClient,
                offlineQueuePlugin,
            )
        }

        private fun createStorageAndEngine(
            queuePath: Path,
            deadLetterPath: Path,
            fileSystem: FileSystem,
            maxRecordFieldSize: Int,
        ): Triple<DiskQueue, DeadLetterQueue, GhostSyncEngine> {
            val queue = DiskQueue(queuePath, fileSystem, maxRecordFieldSize)
            val deadLetters = DeadLetterQueue(
                mainQueue = queue,
                storage = DiskQueue(deadLetterPath, fileSystem, maxRecordFieldSize),
            )
            val engine = GhostSyncEngine(queue, deadLetters)
            return Triple(queue, deadLetters, engine)
        }

        private fun <T : HttpClientEngineConfig> createClients(
            engineFactory: HttpClientEngineFactory<T>,
            queue: DiskQueue,
            httpClientConfig: HttpClientConfig<T>.() -> Unit,
        ): Triple<HttpClient, HttpClient, GhostOfflineQueuePlugin> {
            val client = HttpClient(engineFactory) {
                httpClientConfig()
                install(GhostOfflineQueuePlugin) { diskQueue = queue }
            }
            val offlineQueuePlugin = client.plugin(GhostOfflineQueuePlugin)
            val replayClient = HttpClient(engineFactory) {
                httpClientConfig()
            }
            return Triple(client, replayClient, offlineQueuePlugin)
        }

        private fun defaultDeadLetterPath(queuePath: Path): Path =
            (queuePath.toString() + GhostSyncConstants.DEFAULT_DEAD_LETTER_PATH_SUFFIX).toPath()

        /**
         * Builds a [GhostSyncRuntime] that serializes `flush()` and optionally reacts to an app-
         * supplied [connectivity] signal. The library does not observe the network itself — see
         * [GhostSyncRuntime]'s KDoc.
         */
        fun createRuntime(
            ghostSync: GhostSync,
            scope: CoroutineScope,
            connectivity: Flow<Boolean>? = null,
        ): GhostSyncRuntime = GhostSyncRuntime(
            ghostSync = ghostSync,
            engine = null,
            replayClient = null,
            parentScope = scope,
            connectivity = connectivity,
            onShutdown = null,
        )
    }
}
