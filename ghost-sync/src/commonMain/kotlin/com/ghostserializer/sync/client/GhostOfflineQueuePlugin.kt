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
import io.ktor.utils.io.errors.IOException

/**
 * Installed on Ghost's Ktor client. When a request fails to reach the network — not a business
 * error, a connectivity one — this plugin intercepts the [IOException] at the same extension
 * point [io.ktor.client.plugins.HttpRequestRetry] uses internally
 * ([client.plugin(HttpSend).intercept]), persists it to [DiskQueue], and replaces the original
 * exception with [OfflineQueuedException] so the caller can distinguish "queued for later" from
 * "actually failed."
 *
 * The captured body is whatever 'request' already carries as [OutgoingContent.ByteArrayContent]
 * at this point in the pipeline — already serialized by `GhostContentConverter` upstream, so it
 * is never re-encoded to reach the queue. [enqueueLocked] avoids every allocation it reasonably
 * can on this path: [HeadersBuilder][io.ktor.http.HeadersBuilder] already exposes `entries()`
 * directly (it's backed by the same live map `build()` would otherwise copy into a new [Headers][io.ktor.http.Headers]
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
        val outgoingBody = request.body as? OutgoingContent.ByteArrayContent
        val body = outgoingBody?.bytes() ?: ClientConstants.EMPTY_BODY

        val builderEntries = request.headers.entries()
        val headers = LinkedHashMap<String, String>(builderEntries.size + ClientConstants.HEADER_MAP_SLACK)
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
