package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.LifecycleGate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey
import io.ktor.utils.io.errors.IOException

/**
 * Installed on Ghost's Ktor client. When a request fails to reach the network — not a business
 * error, a connectivity one — this plugin intercepts the [IOException] at the same extension
 * point [io.ktor.client.plugins.HttpRequestRetry] uses internally
 * ([client.plugin(HttpSend).intercept]), persists it to [DiskQueue] via [RequestCapture], and
 * replaces the original exception with [OfflineQueuedException] so the caller can distinguish
 * "queued for later" from "actually failed."
 */
class GhostOfflineQueuePlugin private constructor(
    private val diskQueue: DiskQueue,
) {
    private val requestCapture = RequestCapture()
    private val lifecycleGate = LifecycleGate(
        closedMessage = ClientConstants.PLUGIN_CLOSED_MESSAGE,
        closeWhileBusyMessage = ClientConstants.PLUGIN_CLOSE_WHILE_REQUEST_IN_FLIGHT_MESSAGE,
    )

    /** Used by [com.ghostserializer.sync.GhostSync.close] before tearing down [HttpClient]. */
    internal fun closeForShutdown() {
        lifecycleGate.close()
    }

    internal fun hasInFlightRequests(): Boolean = lifecycleGate.hasActiveSessions()

    private fun intercept(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            lifecycleGate.enter()
            try {
                execute(request)
            } catch (cause: Throwable) {
                if (cause is BodyCaptureException) {
                    throw cause
                }
                if (!Plugin.causesOrIsIOException(cause)) {
                    throw cause
                }
                val url = request.url.buildString()
                try {
                    enqueueLocked(request, url)
                } catch (capture: BodyCaptureException) {
                    throw capture
                }
                throw OfflineQueuedException(url)
            } finally {
                lifecycleGate.leave()
            }
        }
    }

    private suspend fun enqueueLocked(request: HttpRequestBuilder, url: String) {
        val (headers, body) = requestCapture.capture(request)
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
            try {
                config.diskQueue
            } catch (_: Exception) {
                error(ClientConstants.PLUGIN_DISK_QUEUE_MISSING)
            }
            return GhostOfflineQueuePlugin(config.diskQueue)
        }

        override fun install(plugin: GhostOfflineQueuePlugin, scope: HttpClient) {
            plugin.intercept(scope)
        }

        private fun causesOrIsIOException(cause: Throwable): Boolean {
            var current: Throwable? = cause
            while (current != null) {
                if (current is IOException) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
