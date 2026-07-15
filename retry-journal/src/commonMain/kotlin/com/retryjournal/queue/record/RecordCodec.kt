package com.retryjournal.queue.record

import com.ghost.serialization.Ghost
import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.FrozenHttpRequestMeta
import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException

/**
 * Reads and writes the on-disk record framing shared by
 * [DiskQueue][com.retryjournal.queue.disk.DiskQueue] and the dead-letter queue:
 *
 * `Live`:      `[u8 kind][u32 crc32][u64 sequenceId][u32 metaLen][meta bytes][u32 bodyLen][body bytes]`
 * `Tombstone`: `[u8 kind][u32 crc32][u64 targetSequenceId]`
 *
 * The CRC covers only the payload (never the kind byte), computed incrementally across segments
 * via [Crc32.update]/[Crc32.updateLong] so metadata and body never need to be concatenated into
 * one buffer first. The sequence id (not the byte offset) is what
 * [QueueEntryId][com.retryjournal.queue.QueueEntryId] identifies a record by, so it must
 * be part of the checksummed payload — see
 * [QueueEntryId][com.retryjournal.queue.QueueEntryId] for why.
 *
 * [readRecord] always materializes the meta/body bytes it reads. See [RecordScanCodec] for the
 * zero-allocation variant [DiskQueue][com.retryjournal.queue.disk.DiskQueue]'s crash-recovery
 * scan uses instead.
 */
internal object RecordCodec {

    fun writeLive(
        sink: BufferedSink,
        sequenceId: Long,
        metaBytes: ByteArray,
        body: ByteArray
    ): Int {
        val crc = computeLiveCrc(sequenceId, metaBytes, body)

        sink.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
        sink.writeInt(Crc32.finalize(crc))
        sink.writeLong(sequenceId)
        sink.writeInt(metaBytes.size)
        sink.write(metaBytes)
        sink.writeInt(body.size)
        sink.write(body)

        return liveRecordLength(metaBytes.size, body.size)
    }

    fun writeTombstone(sink: BufferedSink, targetSequenceId: Long): Int {
        val crc = Crc32.finalize(Crc32.updateLong(Crc32.INITIAL_VALUE, targetSequenceId))

        sink.writeByte(DiskQueueConstants.RECORD_KIND_TOMBSTONE_INT)
        sink.writeInt(crc)
        sink.writeLong(targetSequenceId)

        return DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    /** Reads one record starting at the source's current position. Never throws on truncation.
     * [maxRecordFieldSize] must be the same value the record was written under — see
     * [DiskQueue][com.retryjournal.queue.disk.DiskQueue]'s own constructor parameter of the
     * same name. */
    fun readRecord(source: BufferedSource, maxRecordFieldSize: Int): RecordReadResult {
        if (source.exhausted()) {
            return RecordReadResult.EndOfFile
        }

        return try {
            val kindByte = source.readByte()
            val expectedCrc = source.readInt()

            when (kindByte) {
                DiskQueueConstants.RECORD_KIND_LIVE_BYTE -> {
                    readLivePayload(source, expectedCrc, maxRecordFieldSize)
                }

                DiskQueueConstants.RECORD_KIND_TOMBSTONE_BYTE -> {
                    readTombstonePayload(source, expectedCrc)
                }

                else -> RecordReadResult.Invalid
            }
        } catch (_: EOFException) {
            RecordReadResult.Invalid
        }
    }

    private fun computeLiveCrc(sequenceId: Long, metaBytes: ByteArray, body: ByteArray): Int {
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        return Crc32.update(crc, body)
    }

    private fun liveRecordLength(metaSize: Int, bodySize: Int): Int =
        DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaSize +
            DiskQueueConstants.LENGTH_FIELD_SIZE + bodySize

    private fun readLivePayload(
        source: BufferedSource,
        expectedCrc: Int,
        maxRecordFieldSize: Int
    ): RecordReadResult {
        val sequenceId = source.readLong()
        val metaBytes = readBoundedByteArray(source, maxRecordFieldSize) ?: return RecordReadResult.Invalid
        val body = readBoundedByteArray(source, maxRecordFieldSize) ?: return RecordReadResult.Invalid

        if (!liveCrcMatches(sequenceId, metaBytes, body, expectedCrc)) {
            return RecordReadResult.Invalid
        }

        val meta = deserializeMeta(metaBytes) ?: return RecordReadResult.Invalid

        return RecordReadResult.Live(
            sequenceId,
            meta,
            metaBytes,
            body,
            liveRecordLength(metaBytes.size, body.size),
        )
    }

    private fun readBoundedByteArray(source: BufferedSource, maxRecordFieldSize: Int): ByteArray? {
        val length = source.readInt()
        if (length !in 0..maxRecordFieldSize) {
            return null
        }
        return source.readByteArray(length.toLong())
    }

    private fun liveCrcMatches(
        sequenceId: Long,
        metaBytes: ByteArray,
        body: ByteArray,
        expectedCrc: Int,
    ): Boolean {
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        crc = Crc32.update(crc, body)
        return Crc32.finalize(crc) == expectedCrc
    }

    private fun deserializeMeta(metaBytes: ByteArray): FrozenHttpRequestMeta? = try {
        Ghost.deserialize<FrozenHttpRequestMeta>(metaBytes)
    } catch (_: Throwable) {
        null
    }

    private fun readTombstonePayload(source: BufferedSource, expectedCrc: Int): RecordReadResult {
        val targetSequenceId = source.readLong()
        val crc = Crc32.finalize(Crc32.updateLong(Crc32.INITIAL_VALUE, value = targetSequenceId))

        if (crc != expectedCrc) return RecordReadResult.Invalid

        return RecordReadResult.Tombstone(
            targetSequenceId,
            recordLength = DiskQueueConstants.TOMBSTONE_RECORD_SIZE
        )
    }
}
