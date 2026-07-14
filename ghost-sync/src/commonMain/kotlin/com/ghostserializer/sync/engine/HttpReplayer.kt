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
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.errors.IOException

/**
 * Replays one queued [QueueEntry] over the wire — the only place [GhostSyncEngine] touches Ktor
 * request-building mechanics. Split out on purpose: [assertSafeToReplayWith] and [send] are now
 * the single implementation both [GhostSyncEngine.flush] and [GhostSyncEngine.getStatus] go
 * through, instead of each keeping its own copy of the plugin-installed-client guard — the exact
 * duplication that once let one of the two drift out of sync with the other.
 */
internal class HttpReplayer {

    /** flush calls this eagerly so a misconfigured client fails immediately, even against an
     * empty queue; [send] also calls it so a caller driving its own [GhostSyncEngine.getStatus]
     * loop can't skip it. Replaying through a client that re-queues its own failures would
     * duplicate every entry that fails again mid-replay — see
     * [REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE]. */
    fun assertSafeToReplayWith(client: HttpClient) {
        check(client.pluginOrNull(GhostOfflineQueuePlugin) == null) { REPLAY_CLIENT_HAS_QUEUE_PLUGIN_MESSAGE }
    }

    suspend fun send(client: HttpClient, entry: QueueEntry): HttpStatusCode {
        assertSafeToReplayWith(client)

        val meta = entry.meta
        val response: HttpResponse = try {
            client.request(meta.url) {
                method = parseMethodOrFallback(meta.method)
                val contentType = applyHeaders(meta.headers)?.let(::parseContentTypeOrNull)
                setBody(
                    if (contentType != null) {
                        ByteArrayContent(entry.body, contentType)
                    } else {
                        entry.body
                    },
                )
            }
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // A stored URL that no longer parses must not permanently wedge the whole queue behind
            // it — same stall pattern method/Content-Type had before their fallbacks. Returning a
            // synthetic 400 routes the entry through the normal dead-letter path instead.
            return HttpStatusCode.BadRequest
        }
        return response.status
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
}
