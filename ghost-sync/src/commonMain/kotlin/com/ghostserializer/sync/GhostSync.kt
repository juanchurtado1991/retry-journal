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
import io.ktor.utils.io.core.Closeable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

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
     * [diskQueue] and [deadLetterQueue]'s own `close()` each throw [IllegalStateException] instead
     * of proceeding if an operation is still in flight on them — see [DiskQueue]'s own "Threading
     * contract" doc for why that's a best-effort check, not an ironclad guarantee. */
    override fun close() {
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
         * [com.ghostserializer.sync.queue.RecordTooLargeException] past this rather than writing
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
            fileSystem: FileSystem = FileSystem.SYSTEM,
            maxRecordFieldSize: Int = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            httpClientConfig: HttpClientConfig<T>.() -> Unit = {},
        ): GhostSync {
            val queue = DiskQueue(queuePath, fileSystem, maxRecordFieldSize)
            val deadLetters = DeadLetterQueue(
                mainQueue = queue,
                storage = DiskQueue(deadLetterPath, fileSystem, maxRecordFieldSize)
            )

            val engine = GhostSyncEngine(queue, deadLetters)

            val client = HttpClient(engineFactory) {
                httpClientConfig()
                install(GhostOfflineQueuePlugin) { diskQueue = queue }
            }
            val replayClient = HttpClient(engineFactory) {
                httpClientConfig()
            }

            return GhostSync(
                queue,
                deadLetters,
                engine,
                client,
                replayClient
            )
        }

        private fun defaultDeadLetterPath(queuePath: Path): Path =
            (queuePath.toString() + GhostSyncConstants.DEFAULT_DEAD_LETTER_PATH_SUFFIX).toPath()
    }
}
