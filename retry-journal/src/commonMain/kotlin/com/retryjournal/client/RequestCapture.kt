package com.retryjournal.client

import com.retryjournal.client.ClientConstants.HEADER_SCRATCH_INITIAL_CAPACITY
import com.retryjournal.queue.FrozenHttpHeaders
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
 * Captures a failed request's headers and body for [RetryJournalOfflineQueuePlugin] to persist.
 *
 * Header capture avoids [Map] entirely: [captureHeaders] writes into reusable scratch arrays on
 * this instance and snapshots them into [FrozenHttpHeaders] with a single [Array.copyOf] per
 * axis — no hash buckets, no map-entry objects. Those scratch arrays are shared across every
 * request the owning [RetryJournalOfflineQueuePlugin] ever captures, so [capture] serializes with
 * [mutex]: one `HttpClient` instance routinely has multiple requests in flight on different
 * coroutines, and without the lock two failing requests racing through [captureHeaders] at once
 * would interleave writes into the same arrays and persist one request's headers under another's
 * queued entry.
 */
internal class RequestCapture(
    private val maxBodyBytes: Int,
) {
    private val mutex = Mutex()
    private var headerNameScratch = Array(HEADER_SCRATCH_INITIAL_CAPACITY) { "" }
    private var headerValueScratch = Array(HEADER_SCRATCH_INITIAL_CAPACITY) { "" }

    suspend fun capture(
        request: HttpRequestBuilder
    ): Pair<FrozenHttpHeaders, ByteArray> = mutex.withLock {
        val outgoingBody = request.body as? OutgoingContent
        val body = captureBody(outgoingBody)
        val headers = captureHeaders(request, outgoingBody)
        headers to body
    }

    private fun captureHeaders(
        request: HttpRequestBuilder,
        outgoingBody: OutgoingContent?,
    ): FrozenHttpHeaders {
        var index = copyRequestHeadersIntoScratch(request)
        index = mergeWireContentType(outgoingBody, index)
        return snapshotHeaderScratch(index)
    }

    private fun copyRequestHeadersIntoScratch(request: HttpRequestBuilder): Int {
        var index = 0
        for (entry in request.headers.entries()) {
            ensureHeaderScratch(index + 1)
            headerNameScratch[index] = entry.key
            headerValueScratch[index] = encodeHeaderValues(entry.value)
            index++
        }
        return index
    }

    private fun mergeWireContentType(outgoingBody: OutgoingContent?, index: Int): Int {
        val wireContentType = outgoingBody?.contentType?.toString() ?: return index
        for (slot in 0 until index) {
            if (headerNameScratch[slot].equals(HttpHeaders.ContentType, ignoreCase = true)) {
                headerValueScratch[slot] = wireContentType
                return index
            }
        }
        ensureHeaderScratch(index + 1)
        headerNameScratch[index] = HttpHeaders.ContentType
        headerValueScratch[index] = wireContentType
        return index + 1
    }

    private fun snapshotHeaderScratch(index: Int): FrozenHttpHeaders {
        val names = Array(index) { headerNameScratch[it] }
        val values = Array(index) { headerValueScratch[it] }
        return FrozenHttpHeaders.fromScratch(names, values, index)
    }

    private fun ensureHeaderScratch(capacity: Int) {
        val oldNames = headerNameScratch
        if (oldNames.size >= capacity) {
            return
        }
        // Doubling (not an exact-fit grow to `capacity`) keeps growth amortized O(1) instead of
        // reallocating on every single header past the initial capacity.
        val newCapacity = maxOf(capacity, oldNames.size * 2)
        val oldValues = headerValueScratch
        headerNameScratch = Array(newCapacity) { index -> if (index < oldNames.size) oldNames[index] else "" }
        headerValueScratch = Array(newCapacity) { index -> if (index < oldValues.size) oldValues[index] else "" }
    }

    private fun encodeHeaderValues(
        values: List<String>
    ): String {
        if (values.isEmpty()) {
            return ""
        }
        if (values.size == 1) {
            return values[0]
        }
        return joinHeaderValues(values)
    }

    private fun joinHeaderValues(values: List<String>): String {
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
            is OutgoingContent.ByteArrayContent -> captureByteArrayBody(content)
            is OutgoingContent.NoContent -> ClientConstants.EMPTY_BODY
            is OutgoingContent.ReadChannelContent -> captureReadChannelBody(content)
            is OutgoingContent.WriteChannelContent -> captureWriteChannelBody(content)
            else -> throw BodyCaptureException(
                ClientConstants.BODY_TYPE_UNSUPPORTED_MESSAGE_PREFIX + content::class.simpleName,
            )
        }
    }

    private fun captureByteArrayBody(content: OutgoingContent.ByteArrayContent): ByteArray {
        if (content.bytes().size > maxBodyBytes) {
            throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
        }
        return content.bytes().copyOf()
    }

    private suspend fun captureReadChannelBody(content: OutgoingContent.ReadChannelContent): ByteArray =
        try {
            readChannelUpTo(content.readFrom())
        } catch (cause: BodyCaptureException) {
            throw cause
        } catch (cause: Throwable) {
            throw BodyCaptureException(ClientConstants.BODY_CAPTURE_FAILED_MESSAGE, cause)
        }

    private suspend fun captureWriteChannelBody(content: OutgoingContent.WriteChannelContent): ByteArray {
        val channel = ByteChannel()
        return coroutineScope {
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

    private suspend fun readChannelUpTo(channel: ByteReadChannel): ByteArray {
        val buffer = Buffer()
        var total = 0
        while (!channel.isClosedForRead) {
            total = appendNextChannelChunk(channel, buffer, total)
        }
        return buffer.readByteArray()
    }

    private suspend fun appendNextChannelChunk(
        channel: ByteReadChannel,
        buffer: Buffer,
        total: Int,
    ): Int {
        channel.awaitContent()
        val available = channel.availableForRead
        if (available <= 0) {
            return total
        }
        val remainingBudget = maxBodyBytes - total
        if (remainingBudget <= 0) {
            throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
        }
        val toRead = minOf(available, remainingBudget).toLong()
        val bytes = channel.readRemaining(toRead).readBytes()
        val newTotal = total + bytes.size
        if (newTotal > maxBodyBytes) {
            throw BodyCaptureException(ClientConstants.BODY_TOO_LARGE_MESSAGE)
        }
        buffer.write(bytes)
        return newTotal
    }
}
