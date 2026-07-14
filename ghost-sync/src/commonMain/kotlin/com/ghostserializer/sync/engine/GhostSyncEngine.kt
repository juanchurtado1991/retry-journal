package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.client.OfflineQueuedException
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_LOWER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.CLIENT_ERROR_STATUS_UPPER_BOUND
import com.ghostserializer.sync.engine.SyncEngineConstants.HEADER_MULTI_VALUE_SEPARATOR
import com.ghostserializer.sync.engine.SyncEngineConstants.REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE
import com.ghostserializer.sync.engine.SyncEngineConstants.RETRY_WORTHY_CLIENT_ERROR_STATUSES
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.errors.IOException

class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    /** Replaying through a client that re-queues its own failures would duplicate every entry
     * that fails again mid-replay — see [REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE]. */
    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {}
    ): FlushResult {
        check(client.pluginOrNull(GhostOfflineQueuePlugin) == null) { REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE }

        var delivered = 0
        var deadLettered = 0

        while (true) {
            val entry = getEntry() ?: return FlushResult(delivered, deadLettered, stoppedEarly = false)
            val status = trySend(client, entry) ?: return FlushResult(delivered, deadLettered, stoppedEarly = true)

            when {
                status.isSuccess() -> {
                    queue.remove(entry.id)
                    delivered++
                    onProgress(FlushProgress.Delivered(entry.id))
                }

                shouldDeadLetter(status) -> {
                    try {
                        deadLetterQueue.record(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
                        queue.remove(entry.id)
                        deadLettered++
                        onProgress(FlushProgress.DeadLettered(entry.id))
                    } catch (_: Throwable) {
                        return FlushResult(delivered, deadLettered, stoppedEarly = true)
                    }
                }

                else -> return FlushResult(delivered, deadLettered, stoppedEarly = true)
            }
        }
    }

    suspend fun getEntry(): QueueEntry? = queue.peek()

    /** Same duplicate-enqueue risk as [flush] if [client] has [GhostOfflineQueuePlugin] installed
     * — a caller driving its own [getEntry]/[getStatus] loop hits it exactly the same way a
     * misconfigured [flush] call would. */
    suspend fun getStatus(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        check(client.pluginOrNull(GhostOfflineQueuePlugin) == null) { REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE }
        return send(client, entry)
    }

    private suspend fun trySend(client: HttpClient, entry: QueueEntry): HttpStatusCode? = try {
        getStatus(client, entry)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: IOException) {
        null
    } catch (_: OfflineQueuedException) {
        null
    } catch (_: Throwable) {
        null
    }

    private suspend fun send(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        val meta = entry.meta
        val response: HttpResponse = client.request(meta.url) {
            method = HttpMethod.parse(meta.method)
            val contentType = applyHeaders(meta.headers)?.let(::parseContentTypeOrNull)
            setBody(
                if (contentType != null) {
                    ByteArrayContent(entry.body, contentType)
                } else {
                    entry.body
                },
            )
        }
        return response.status
    }

    /** A stored Content-Type that no longer parses must not permanently wedge the whole queue
     * behind it: [flush] always starts from the oldest entry, so an uncaught exception here would
     * stop every future `flush()` on this same entry forever, blocking everything queued after it
     * too. Falling back to sending the raw body without a Content-Type override still lets the
     * request reach the server, which can then reject it (4xx) through the normal dead-letter
     * path instead of a permanent stall. */
    private fun parseContentTypeOrNull(value: String): ContentType? = try {
        ContentType.parse(value)
    } catch (_: Exception) {
        null
    }

    /** [HeaderDispatch] routes known header names without a [Map] lookup. */
    private fun HttpRequestBuilder.applyHeaders(headers: FrozenHttpHeaders): String? {
        var contentType: String? = null
        headers.forEach { name, value ->
            when (HeaderDispatch.slotFor(name)) {
                HeaderDispatch.SLOT_CONTENT_TYPE -> contentType = value
                HeaderDispatch.SLOT_CONTENT_LENGTH -> Unit
                else -> appendHeaderValues(name, value)
            }
        }
        return contentType
    }

    private fun HttpRequestBuilder.appendHeaderValues(name: String, value: String) {
        var separatorIndex = value.indexOf(HEADER_MULTI_VALUE_SEPARATOR)
        if (separatorIndex < 0) {
            header(name, value)
            return
        }
        var start = 0
        while (start <= value.length) {
            val end = if (separatorIndex < 0) {
                value.length
            } else {
                separatorIndex
            }
            header(name, value.substring(start, end))
            if (separatorIndex < 0) {
                break
            }
            start = separatorIndex + 1
            separatorIndex = value.indexOf(HEADER_MULTI_VALUE_SEPARATOR, start)
        }
    }

    private fun shouldDeadLetter(status: HttpStatusCode): Boolean {
        val value = status.value
        return value in CLIENT_ERROR_STATUS_LOWER_BOUND..CLIENT_ERROR_STATUS_UPPER_BOUND &&
            value !in RETRY_WORTHY_CLIENT_ERROR_STATUSES
    }
}
