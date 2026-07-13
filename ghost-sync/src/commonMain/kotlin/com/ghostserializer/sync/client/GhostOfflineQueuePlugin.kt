package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Installed on Ghost's Ktor client. When a request fails to reach the network — not a business
 * error, a connectivity one — this plugin intercepts the [IOException] at the same extension
 * point [io.ktor.client.plugins.HttpRequestRetry] uses internally
 * ([client.plugin(HttpSend).intercept]), persists it to [DiskQueue], and replaces the original
 * exception with [OfflineQueuedException] so the caller can distinguish "queued for later" from
 * "actually failed."
 *
 * The captured body is whatever bytes 'request' already carries at this point in the pipeline —
 * already serialized by `GhostContentConverter` upstream, so it is never re-encoded to reach the
 * queue. That body isn't always an in-memory [OutgoingContent.ByteArrayContent] though: a file or
 * image upload (`MultiPartFormDataContent`, a streamed file) is an
 * [OutgoingContent.WriteChannelContent] or [OutgoingContent.ReadChannelContent] instead — those
 * still get fully read into bytes here (see [captureBody]) so they queue correctly too, not
 * silently as an empty body.
 *
 * [enqueueLocked] avoids every allocation it reasonably can on the header-copying part of this
 * path: [HeadersBuilder][io.ktor.http.HeadersBuilder] already exposes `entries()` directly (it's
 * backed by the same live map `build()` would otherwise copy into a new [Headers][io.ktor.http.Headers]
 * instance just to read back out), the header map is pre-sized instead of grown by rehashing, and
 * the URL is rendered to a string exactly once and reused for both the queued record and the
 * thrown exception.
 */
class GhostOfflineQueuePlugin private constructor(
    private val diskQueue: DiskQueue,
) {
    private fun intercept(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (_: IOException) {
                val url = request.url.buildString()
                enqueueLocked(request, url)
                throw OfflineQueuedException(url)
            }
        }
    }

    private suspend fun enqueueLocked(request: HttpRequestBuilder, url: String) {
        val outgoingBody = request.body as? OutgoingContent
        val body = captureBody(outgoingBody)

        val builderEntries = request.headers.entries()
        val headersInitialCapacity = builderEntries.size + ClientConstants.HEADER_MAP_SLACK
        val headers = LinkedHashMap<String, String>(headersInitialCapacity)
        
        for (entry in builderEntries) {
            headers[entry.key] = entry.value.firstOrNull().orEmpty()
        }
        // request.headers can carry a Content-Type the caller declared explicitly (e.g. via
        // contentType(...)) that no longer matches what ContentNegotiation put on the wire —
        // engines send OutgoingContent's own contentType, not that stale label, so a plain
        // caller-declared mismatch is harmless for the request this plugin is intercepting.
        // Replaying it later isn't: GhostSyncEngine.send() rebuilds a bare ByteArrayContent from
        // exactly what's captured here, so a stale label would ship the real (correctly encoded)
        // bytes under the wrong Content-Type and the server would reject every single replay.
        outgoingBody?.contentType?.let { headers[HttpHeaders.ContentType] = it.toString() }

        diskQueue.enqueue(
            method = request.method.value,
            url = url,
            headers = headers,
            body = body,
        )
    }

    /** [OutgoingContent.ByteArrayContent] (the common case — a serialized DTO) is already bytes,
     * no work needed. A file/image upload built with `MultiPartFormDataContent` or a streamed
     * file is an [OutgoingContent.ReadChannelContent] or [OutgoingContent.WriteChannelContent]
     * instead — both get drained into an in-memory [ByteChannel] so the exact bytes that would
     * have gone over the wire are what ends up on disk. */
    private suspend fun captureBody(content: OutgoingContent?): ByteArray = when (content) {
        is OutgoingContent.ByteArrayContent -> content.bytes()

        is OutgoingContent.ReadChannelContent ->
            content.readFrom().readRemaining().readBytes()

        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            coroutineScope {
                launch {
                    content.writeTo(channel)
                    channel.close()
                }
                channel.readRemaining().readBytes()
            }
        }

        else -> ClientConstants.EMPTY_BODY
    }

    companion object Plugin : HttpClientPlugin<GhostOfflineQueueConfig, GhostOfflineQueuePlugin> {
        override val key: AttributeKey<GhostOfflineQueuePlugin> =
            AttributeKey(ClientConstants.PLUGIN_ATTRIBUTE_KEY_NAME)

        override fun prepare(
            block: GhostOfflineQueueConfig.() -> Unit
        ): GhostOfflineQueuePlugin {
            val config = GhostOfflineQueueConfig().apply(block)
            return GhostOfflineQueuePlugin(config.diskQueue)
        }

        override fun install(plugin: GhostOfflineQueuePlugin, scope: HttpClient) {
            plugin.intercept(scope)
        }
    }
}
