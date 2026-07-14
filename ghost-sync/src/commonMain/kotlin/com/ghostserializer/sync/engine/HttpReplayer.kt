package com.ghostserializer.sync.engine

import com.ghostserializer.sync.client.GhostOfflineQueuePlugin
import com.ghostserializer.sync.engine.SyncEngineConstants.HEADER_MULTI_VALUE_SEPARATOR
import com.ghostserializer.sync.engine.SyncEngineConstants.REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.client.HttpClient
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLParserException
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.discard
import io.ktor.utils.io.errors.IOException

/**
 * Replays one queued [QueueEntry] over the wire — the only place [GhostSyncEngine] touches Ktor
 * request-building mechanics. Split out on purpose: [assertSafeToReplayWith] and [send] are now
 * the single implementation [HeadReplayExecutor] uses for replay.
 */
internal class HttpReplayer {

    /** Called before replay so a misconfigured client fails immediately, even against an empty
     * queue. Replaying through a client that re-queues its own failures would duplicate every
     * entry that fails again mid-replay — see [REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE]. */
    fun assertSafeToReplayWith(client: HttpClient) {
        check(client.pluginOrNull(GhostOfflineQueuePlugin) == null) { REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE }
    }

    suspend fun send(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        assertSafeToReplayWith(client)

        if (urlFailsToParse(entry.meta.url)) {
            return HttpStatusCode.BadRequest
        }

        val response: HttpResponse = client.request(entry.meta.url) {
            configureReplayRequest(entry)
        }
        return try {
            response.status
        } finally {
            response.bodyAsChannel().discard()
        }
    }

    private fun HttpRequestBuilder.configureReplayRequest(entry: QueueEntry) {
        method = parseMethodOrFallback(entry.meta.method)
        val contentType = applyHeaders(entry.meta.headers)?.let(::parseContentTypeOrNull)
        setBody(buildReplayBody(entry.body, contentType))
    }

    private fun buildReplayBody(body: ByteArray, contentType: ContentType?) =
        if (contentType != null) {
            ByteArrayContent(body, contentType)
        } else {
            body
        }

    /** Returns true when [url] cannot be parsed into a replayable request URL — checked before
     * [send] touches the network so a corrupt stored URL dead-letters instead of stalling the
     * queue, without treating unrelated runtime faults during the round-trip as 4xx. */
    private fun urlFailsToParse(url: String): Boolean = try {
        URLBuilder(url).build()
        false
    } catch (_: URLParserException) {
        true
    } catch (_: IllegalArgumentException) {
        true
    }

    /** A stored HTTP method that no longer parses must not permanently wedge the whole queue
     * behind it — same reasoning as [parseContentTypeOrNull] below. Falling back to a custom
     * [HttpMethod] built from the raw stored string still lets the request reach the server,
     * which can then reject it (4xx) through the normal dead-letter path instead of a permanent
     * stall on every future `flush()`. */
    private fun parseMethodOrFallback(value: String): HttpMethod = try {
        HttpMethod.parse(value)
    } catch (_: Exception) {
        HttpMethod(value)
    }

    /** A stored Content-Type that no longer parses must not permanently wedge the whole queue
     * behind it: [GhostSyncEngine.flush] always starts from the oldest entry, so an uncaught
     * exception here would stop every future `flush()` on this same entry forever, blocking
     * everything queued after it too. Falling back to sending the raw body without a Content-Type
     * override still lets the request reach the server, which can then reject it (4xx) through
     * the normal dead-letter path instead of a permanent stall. */
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
                HeaderDispatch.SLOT_SKIP -> Unit
                else -> appendSplitHeaderValues(name, value)
            }
        }
        return contentType
    }

    private fun HttpRequestBuilder.appendSplitHeaderValues(name: String, value: String) {
        for (segment in splitHeaderValues(value)) {
            header(name, segment)
        }
    }

    private fun splitHeaderValues(value: String): List<String> {
        if (value.indexOf(HEADER_MULTI_VALUE_SEPARATOR) < 0) {
            return listOf(value)
        }
        val segments = mutableListOf<String>()
        var start = 0
        var separatorIndex = value.indexOf(HEADER_MULTI_VALUE_SEPARATOR, start)
        while (start <= value.length) {
            val end = if (separatorIndex < 0) value.length else separatorIndex
            segments.add(value.substring(start, end))
            if (separatorIndex < 0) {
                break
            }
            start = separatorIndex + 1
            separatorIndex = value.indexOf(HEADER_MULTI_VALUE_SEPARATOR, start)
        }
        return segments
    }
}
