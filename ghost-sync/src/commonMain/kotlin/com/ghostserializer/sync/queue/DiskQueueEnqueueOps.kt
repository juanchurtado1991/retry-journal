package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_PACKABLE_RECORD_LENGTH
import com.ghostserializer.sync.queue.platform.currentTimeMillis
import com.ghostserializer.sync.queue.record.PackedIndexEntry
import com.ghostserializer.sync.queue.record.RecordCodec
import com.ghostserializer.sync.queue.record.RecordTooLargeException
import okio.IOException

/** Validates and appends live records during [DiskQueue.enqueue]. */
internal object DiskQueueEnqueueOps {

    fun encodeMeta(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
    ): ByteArray {
        val meta = FrozenHttpRequestMeta(
            method = method,
            url = url,
            headers = headers,
            enqueuedAtMillis = currentTimeMillis(),
        )
        return Ghost.encodeToBytes(meta)
    }

    fun validateFieldSizes(queue: DiskQueue, metaBytes: ByteArray, body: ByteArray) {
        if (metaBytes.size > queue.maxRecordFieldSize) {
            throw RecordTooLargeException(
                DiskQueueConstants.META_FIELD_NAME,
                metaBytes.size,
                queue.maxRecordFieldSize,
            )
        }
        if (body.size > queue.maxRecordFieldSize) {
            throw RecordTooLargeException(
                DiskQueueConstants.BODY_FIELD_NAME,
                body.size,
                queue.maxRecordFieldSize,
            )
        }
        val packedLength = computePackedLiveRecordLength(metaBytes, body)
        if (packedLength > MAX_PACKABLE_RECORD_LENGTH) {
            throw RecordTooLargeException(
                DiskQueueConstants.RECORD_FIELD_NAME,
                packedLength,
                MAX_PACKABLE_RECORD_LENGTH,
            )
        }
    }

    fun computePackedLiveRecordLength(metaBytes: ByteArray, body: ByteArray): Int =
        DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
            DiskQueueConstants.LENGTH_FIELD_SIZE + body.size

    fun appendLiveRecordLocked(
        queue: DiskQueue,
        sequenceId: Long,
        metaBytes: ByteArray,
        body: ByteArray,
        packedLength: Int,
    ) {
        val offsetBefore = queue.fileLength
        val sink = queue.fileHandles.appendSink()
        try {
            val written = RecordCodec.writeLive(sink, sequenceId, metaBytes, body)
            check(written == packedLength)
            sink.flush()

            queue.fileLength += written
            queue.liveOffsetsBySequence[sequenceId] = PackedIndexEntry.pack(written, offsetBefore)
            queue.nextSequenceId++
            DiskQueueIndexSync.bumpGenerationLocked(queue)
        } catch (e: IOException) {
            queue.fileHandles.closeAppendSink()
            DiskQueueRemovalOps.truncateFileLocked(queue, offsetBefore)
            throw e
        }
    }
}

internal fun DiskQueue.encodeEnqueueMeta(
    method: String,
    url: String,
    headers: FrozenHttpHeaders,
): ByteArray = DiskQueueEnqueueOps.encodeMeta(method, url, headers)

internal fun DiskQueue.validateEnqueueFieldSizes(metaBytes: ByteArray, body: ByteArray) =
    DiskQueueEnqueueOps.validateFieldSizes(this, metaBytes, body)

internal fun DiskQueue.computePackedLiveRecordLength(metaBytes: ByteArray, body: ByteArray): Int =
    DiskQueueEnqueueOps.computePackedLiveRecordLength(metaBytes, body)

internal fun DiskQueue.appendLiveRecordLocked(
    sequenceId: Long,
    metaBytes: ByteArray,
    body: ByteArray,
    packedLength: Int,
) = DiskQueueEnqueueOps.appendLiveRecordLocked(this, sequenceId, metaBytes, body, packedLength)
