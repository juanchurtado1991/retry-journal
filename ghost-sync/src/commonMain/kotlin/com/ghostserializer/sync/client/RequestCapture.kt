package com.ghostserializer.sync.client

import com.ghostserializer.sync.queue.FrozenHttpHeaders
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer

/**
 * Captures a failed request's headers and body for [GhostOfflineQueuePlugin] to persist.
 *
 * Header capture avoids [Map] entirely: [captureHeaders] writes into reusable scratch arrays on
 * this instance and snapshots them into [FrozenHttpHeaders] with a single [Array.copyOf] per
 * axis — no hash buckets, no map-entry objects. Those scratch arrays are shared across every
 * request the owning [GhostOfflineQueuePlugin] ever captures, so [capture] serializes with
 * [mutex]: one `HttpClient` instance routinely has multiple requests in flight on different
 * coroutines, and without the lock two failing requests racing through [captureHeaders] at once
 * would interleave writes into the same arrays and persist one request's headers under another's
 * queued entry.
 */
internal class RequestCapture(
    private val maxBodyBytes: Int,
) {
    private val mutex = Mutex()
    private var headerNameScratch = Array(ClientConstants.HEADER_SCRATCH_INITIAL_CAPACITY) { "" }
    private var headerValueScratch = Array(ClientConstants.HEADER_SCRATCH_INITIAL_CAPACITY) { "" }

    suspend fun capture(request: HttpRequestBuilder): Pair<FrozenHttpHeaders, ByteArray> = mutex.withLock {
        val outgoingBody = request.body as? OutgoingContent
        val body = captureBody(outgoingBody)
        val headers = captureHeaders(request, outgoingBody)
        headers to body
    }

    private fun captureHeaders(
        request: HttpRequestBuilder,
        outgoingBody: OutgoingContent?,
    ): FrozenHttpHeaders {
        var index = 0
        for (entry in request.headers.entries()) {
            ensureHeaderScratch(index + 1)
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

    @Suppress("DEPRECATION")
    private suspend fun captureBody(content: OutgoingContent?): ByteArray {
        if (content == null) {
            return ClientConstants.EMPTY_BODY
        }
        return when (content) {
            is OutgoingContent.ByteArrayContent -> {
                if (content.bytes().size > maxBodyBytes) {
                    throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
                }
                content.bytes()
            }

            is OutgoingContent.NoContent -> ClientConstants.EMPTY_BODY

            is OutgoingContent.ReadChannelContent -> try {
                readChannelUpTo(content.readFrom())
            } catch (cause: BodyCaptureException) {
                throw cause
            } catch (cause: Throwable) {
                throw BodyCaptureException(ClientConstants.BODY_CAPTURE_FAILED_MESSAGE, cause)
            }

            is OutgoingContent.WriteChannelContent -> {
                val channel = ByteChannel()
                coroutineScope {
                    val writeJob = launch { content.writeTo(channel) }
                    try {
                        writeJob.join()
                        readChannelUpTo(channel)
                    } catch (cause: BodyCaptureException) {
                        throw cause
                    } catch (cause: Throwable) {
                        throw BodyCaptureException(ClientConstants.BODY_CAPTURE_FAILED_MESSAGE, cause)
                    } finally {
                        channel.close()
                    }
                }
            }

            else -> throw BodyCaptureException(
                ClientConstants.BODY_TYPE_UNSUPPORTED_MESSAGE_PREFIX + content::class.simpleName,
            )
        }
    }

    private suspend fun readChannelUpTo(channel: ByteReadChannel): ByteArray {
        val buffer = Buffer()
        var total = 0
        while (!channel.isClosedForRead) {
            channel.awaitContent()
            val available = channel.availableForRead
            if (available <= 0) {
                continue
            }
            val remainingBudget = maxBodyBytes - total
            if (remainingBudget <= 0) {
                throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
            }
            val toRead = minOf(available, remainingBudget).toLong()
            val packet = channel.readRemaining(toRead)
            val bytes = packet.readBytes()
            total += bytes.size
            if (total > maxBodyBytes) {
                throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
            }
            buffer.write(bytes)
        }
        return buffer.readByteArray()
    }
}
