package com.retryjournal.client

import com.retryjournal.client.ClientConstants.PLUGIN_CLOSED_MESSAGE
import com.retryjournal.client.ClientConstants.PLUGIN_CLOSE_WHILE_REQUEST_IN_FLIGHT_MESSAGE
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.LifecycleGate
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
class RetryJournalOfflineQueuePlugin private constructor(
    private val diskQueue: DiskQueue,
) {
    private val requestCapture = RequestCapture(diskQueue.maxRecordFieldSize)
    private val lifecycleGate = LifecycleGate(
        closedMessage = PLUGIN_CLOSED_MESSAGE,
        closeWhileBusyMessage = PLUGIN_CLOSE_WHILE_REQUEST_IN_FLIGHT_MESSAGE,
    )

    /** Used by [com.retryjournal.RetryJournal.close] before tearing down [HttpClient]. */
    internal fun closeForShutdown() = lifecycleGate.close()

    private fun intercept(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            lifecycleGate.enter()
            try {
                execute(request)
            } catch (cause: Throwable) {
                handleSendFailure(request, cause)
            } finally {
                lifecycleGate.leave()
            }
        }
    }

    private suspend fun handleSendFailure(
        request: HttpRequestBuilder,
        cause: Throwable
    ): Nothing {
        if (cause is BodyCaptureException) {
            throw cause
        }
        if (!causesOrIsIOException(cause)) {
            throw cause
        }
        val url = request.url.buildString()
        enqueueLocked(request, url)
        throw OfflineQueuedException(url)
    }

    private suspend fun enqueueLocked(
        request: HttpRequestBuilder,
        url: String
    ) {
        val (headers, body) = requestCapture
            .capture(request)

        diskQueue.enqueue(
            method = request.method.value,
            url = url,
            headers = headers,
            body = body,
        )
    }

    companion object Plugin : HttpClientPlugin<RetryJournalOfflineQueueConfig, RetryJournalOfflineQueuePlugin> {
        override val key: AttributeKey<RetryJournalOfflineQueuePlugin> =
            AttributeKey(ClientConstants.PLUGIN_ATTRIBUTE_KEY_NAME)

        override fun prepare(
            block: RetryJournalOfflineQueueConfig.() -> Unit
        ): RetryJournalOfflineQueuePlugin {
            val config = RetryJournalOfflineQueueConfig().apply(block)
            requireConfiguredDiskQueue(config)
            return RetryJournalOfflineQueuePlugin(config.diskQueue)
        }

        override fun install(plugin: RetryJournalOfflineQueuePlugin, scope: HttpClient) {
            plugin.intercept(scope)
        }

        private fun requireConfiguredDiskQueue(config: RetryJournalOfflineQueueConfig) {
            try {
                config.diskQueue
            } catch (_: Exception) {
                error(ClientConstants.PLUGIN_DISK_QUEUE_MISSING)
            }
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
