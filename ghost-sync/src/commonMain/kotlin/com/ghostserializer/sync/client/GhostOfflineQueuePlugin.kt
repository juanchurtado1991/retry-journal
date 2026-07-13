package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.DiskQueue
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
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
 * is never re-encoded to reach the queue.
 */
class GhostOfflineQueuePlugin private constructor(
    private val diskQueue: DiskQueue,
) {
    private fun intercept(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (_: IOException) {
                enqueueLocked(request)
                throw OfflineQueuedException(request.url.buildString())
            }
        }
    }

    private suspend fun enqueueLocked(request: HttpRequestBuilder) {
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val headers = request.headers.build().entries()
            .associate { (name, values) -> name to values.firstOrNull().orEmpty() }

        diskQueue.enqueue(
            method = request.method.value,
            url = request.url.buildString(),
            headers = headers,
            body = body,
        )
    }

    companion object Plugin : HttpClientPlugin<GhostOfflineQueueConfig, GhostOfflineQueuePlugin> {
        override val key: AttributeKey<GhostOfflineQueuePlugin> = AttributeKey("GhostOfflineQueuePlugin")

        override fun prepare(block: GhostOfflineQueueConfig.() -> Unit): GhostOfflineQueuePlugin {
            val config = GhostOfflineQueueConfig().apply(block)
            return GhostOfflineQueuePlugin(config.diskQueue)
        }

        override fun install(plugin: GhostOfflineQueuePlugin, scope: HttpClient) {
            plugin.intercept(scope)
        }
    }
}
