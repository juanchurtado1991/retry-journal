package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
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
 * Header capture avoids [Map] entirely: [captureHeaders] writes into reusable scratch arrays on
 * this plugin instance and snapshots them into [FrozenHttpHeaders] with a single [Array.copyOf]
 * per axis — no hash buckets, no map-entry objects.
 */
class GhostOfflineQueuePlugin private constructor(
    private val diskQueue: DiskQueue,
) {
    private var headerNameScratch = Array(ClientConstants.HEADER_SCRATCH_INITIAL_CAPACITY) { "" }
    private var headerValueScratch = Array(ClientConstants.HEADER_SCRATCH_INITIAL_CAPACITY) { "" }

    private fun intercept(client: HttpClient) {
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (e: IOException) {
                val url = request.url.buildString()
                try {
                    enqueueLocked(request, url)
                } catch (capture: BodyCaptureException) {
                    throw capture
                }
                throw OfflineQueuedException(url)
            }
        }
    }

    private suspend fun enqueueLocked(request: HttpRequestBuilder, url: String) {
        val outgoingBody = request.body as? OutgoingContent
        val body = captureBody(outgoingBody)
        val headers = captureHeaders(request, outgoingBody)

        diskQueue.enqueue(
            method = request.method.value,
            url = url,
            headers = headers,
            body = body,
        )
    }

    private fun captureHeaders(
        request: HttpRequestBuilder,
        outgoingBody: OutgoingContent?,
    ): FrozenHttpHeaders {
        val builderEntries = request.headers.entries()
        var count = 0
        for (entry in builderEntries) {
            count++
        }
        ensureHeaderScratch(count)

        var index = 0
        for (entry in builderEntries) {
            headerNameScratch[index] = entry.key
            headerValueScratch[index] = encodeHeaderValues(entry.value)
            index++
        }

        val wireContentType = outgoingBody?.contentType?.toString()
        if (wireContentType != null) {
            var replaced = false
            for (slot in 0 until index) {
                if (headerNameScratch[slot].equals(HttpHeaders.ContentType, ignoreCase = true)) {
                    headerValueScratch[slot] = wireContentType
                    replaced = true
                    break
                }
            }
            if (!replaced) {
                ensureHeaderScratch(index + 1)
                headerNameScratch[index] = HttpHeaders.ContentType
                headerValueScratch[index] = wireContentType
                index++
            }
        }

        val names = Array(index) { headerNameScratch[it] }
        val values = Array(index) { headerValueScratch[it] }
        return FrozenHttpHeaders.fromScratch(names, values, index)
    }

    private fun ensureHeaderScratch(capacity: Int) {
        if (headerNameScratch.size >= capacity) {
            return
        }
        headerNameScratch = Array(capacity) { "" }
        headerValueScratch = Array(capacity) { "" }
    }

    private fun encodeHeaderValues(values: List<String>): String {
        if (values.isEmpty()) {
            return ""
        }
        if (values.size == 1) {
            return values[0]
        }
        val separator = ClientConstants.HEADER_MULTI_VALUE_SEPARATOR
        var totalLength = separator.length * (values.size - 1)
        for (value in values) {
            totalLength += value.length
        }
        val builder = StringBuilder(totalLength)
        builder.append(values[0])
        for (valueIndex in 1 until values.size) {
            builder.append(separator)
            builder.append(values[valueIndex])
        }
        return builder.toString()
    }

    private suspend fun captureBody(content: OutgoingContent?): ByteArray {
        if (content == null) {
            return ClientConstants.EMPTY_BODY
        }
        return when (content) {
            is OutgoingContent.ByteArrayContent -> content.bytes()

            is OutgoingContent.NoContent -> ClientConstants.EMPTY_BODY

            is OutgoingContent.ReadChannelContent -> try {
                content.readFrom().readRemaining().readBytes()
            } catch (cause: Throwable) {
                throw BodyCaptureException(ClientConstants.BODY_CAPTURE_FAILED_MESSAGE, cause)
            }

            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel()
                coroutineScope {
                    val writeJob = launch {
                        content.writeTo(channel)
                    }
                    try {
                        writeJob.join()
                    } catch (cause: Throwable) {
                        channel.close()
                        throw BodyCaptureException(ClientConstants.BODY_CAPTURE_FAILED_MESSAGE, cause)
                    } finally {
                        channel.close()
                    }
                    channel.readRemaining().readBytes()
                }
            }

            else -> throw BodyCaptureException(
                ClientConstants.BODY_TYPE_UNSUPPORTED_MESSAGE_PREFIX + content::class.simpleName,
            )
        }
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
    }
}
