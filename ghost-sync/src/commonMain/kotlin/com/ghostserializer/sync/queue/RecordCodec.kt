package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException

/**
 * Reads and writes the on-disk record framing shared by [DiskQueue] and the dead-letter queue:
 *
 * `Live`:      `[u8 kind][u32 crc32][u64 sequenceId][u32 metaLen][meta bytes][u32 bodyLen][body bytes]`
 * `Tombstone`: `[u8 kind][u32 crc32][u64 targetSequenceId]`
 *
 * The CRC covers only the payload (never the kind byte), computed incrementally across segments
 * via [Crc32.update]/[Crc32.updateLong] so metadata and body never need to be concatenated into
 * one buffer first. The sequence id (not the byte offset) is what [QueueEntryId] identifies a
 * record by, so it must be part of the checksummed payload — see [QueueEntryId] for why.
 */
internal object RecordCodec {

    fun writeLive(sink: BufferedSink, sequenceId: Long, metaBytes: ByteArray, body: ByteArray): Int {
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        crc = Crc32.update(crc, body)

        sink.writeByte(RecordKind.Live.byteValue.toInt())
        sink.writeInt(Crc32.finalize(crc))
        sink.writeLong(sequenceId)
        sink.writeInt(metaBytes.size)
        sink.write(metaBytes)
        sink.writeInt(body.size)
        sink.write(body)

        return DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
            DiskQueueConstants.LENGTH_FIELD_SIZE + body.size
    }

    fun writeTombstone(sink: BufferedSink, targetSequenceId: Long): Int {
        val crc = Crc32.finalize(Crc32.updateLong(Crc32.INITIAL_VALUE, targetSequenceId))

        sink.writeByte(RecordKind.Tombstone.byteValue.toInt())
        sink.writeInt(crc)
        sink.writeLong(targetSequenceId)

        return DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    /** Reads one record starting at the source's current position. Never throws on truncation.
     * [maxRecordFieldSize] must be the same value the record was written under — see
     * [DiskQueue]'s own constructor parameter of the same name. */
    fun readRecord(source: BufferedSource, maxRecordFieldSize: Int): RecordReadResult {
        if (source.exhausted()) {
            return RecordReadResult.EndOfFile
        }

        return try {
            val kind = RecordKind.fromByte(source.readByte())
            val expectedCrc = source.readInt()

            when (kind) {
                RecordKind.Live -> readLivePayload(source, expectedCrc, maxRecordFieldSize)
                RecordKind.Tombstone -> readTombstonePayload(source, expectedCrc)
                null -> RecordReadResult.Invalid
            }
        } catch (e: EOFException) {
            RecordReadResult.Invalid
        }
    }

    private fun readLivePayload(source: BufferedSource, expectedCrc: Int, maxRecordFieldSize: Int): RecordReadResult {
        val sequenceId = source.readLong()

        val metaLen = source.readInt()
        if (metaLen < 0 || metaLen > maxRecordFieldSize) {
            return RecordReadResult.Invalid
        }
        val metaBytes = source.readByteArray(metaLen.toLong())

        val bodyLen = source.readInt()
        if (bodyLen < 0 || bodyLen > maxRecordFieldSize) {
            return RecordReadResult.Invalid
        }
        val body = source.readByteArray(bodyLen.toLong())

        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        crc = Crc32.update(crc, body)
        if (Crc32.finalize(crc) != expectedCrc) {
            return RecordReadResult.Invalid
        }

        val meta = Ghost.deserialize<FrozenHttpRequestMeta>(metaBytes)
        val recordLength = DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
            DiskQueueConstants.LENGTH_FIELD_SIZE + body.size

        return RecordReadResult.Live(sequenceId, meta, metaBytes, body, recordLength)
    }

    private fun readTombstonePayload(source: BufferedSource, expectedCrc: Int): RecordReadResult {
        val targetSequenceId = source.readLong()
        val crc = Crc32.finalize(Crc32.updateLong(Crc32.INITIAL_VALUE, targetSequenceId))
        if (crc != expectedCrc) {
            return RecordReadResult.Invalid
        }
        return RecordReadResult.Tombstone(targetSequenceId, DiskQueueConstants.TOMBSTONE_RECORD_SIZE)
    }

    /** Same framing and the same full CRC verification as [readRecord], but for [DiskQueue]'s
     * crash-recovery scan: it only ever needs [RecordScanResult]'s sequence id and record length
     * to rebuild the offset index, so meta/body bytes are hashed in bounded chunks
     * ([DiskQueueConstants.SCAN_CHUNK_SIZE]) instead of read into one allocation the size of the
     * field — a queue full of large file/image bodies shouldn't need gigabytes of heap just to
     * open. [maxRecordFieldSize] must be the same value the record was written under. */
    fun scanRecord(source: BufferedSource, maxRecordFieldSize: Int): RecordScanResult {
        if (source.exhausted()) {
            return RecordScanResult.EndOfFile
        }

        return try {
            val kind = RecordKind.fromByte(source.readByte())
            val expectedCrc = source.readInt()

            when (kind) {
                RecordKind.Live -> scanLivePayload(source, expectedCrc, maxRecordFieldSize)
                RecordKind.Tombstone -> scanTombstonePayload(source, expectedCrc)
                null -> RecordScanResult.Invalid
            }
        } catch (e: EOFException) {
            RecordScanResult.Invalid
        }
    }

    private fun scanLivePayload(source: BufferedSource, expectedCrc: Int, maxRecordFieldSize: Int): RecordScanResult {
        val sequenceId = source.readLong()

        val metaLen = source.readInt()
        if (metaLen < 0 || metaLen > maxRecordFieldSize) {
            return RecordScanResult.Invalid
        }
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = hashChunked(source, metaLen, crc)

        val bodyLen = source.readInt()
        if (bodyLen < 0 || bodyLen > maxRecordFieldSize) {
            return RecordScanResult.Invalid
        }
        crc = hashChunked(source, bodyLen, crc)

        if (Crc32.finalize(crc) != expectedCrc) {
            return RecordScanResult.Invalid
        }

        val recordLength = DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaLen +
            DiskQueueConstants.LENGTH_FIELD_SIZE + bodyLen

        return RecordScanResult.Live(sequenceId, recordLength)
    }

    private fun scanTombstonePayload(source: BufferedSource, expectedCrc: Int): RecordScanResult {
        val targetSequenceId = source.readLong()
        val crc = Crc32.finalize(Crc32.updateLong(Crc32.INITIAL_VALUE, targetSequenceId))
        if (crc != expectedCrc) {
            return RecordScanResult.Invalid
        }
        return RecordScanResult.Tombstone(targetSequenceId, DiskQueueConstants.TOMBSTONE_RECORD_SIZE)
    }

    private fun hashChunked(source: BufferedSource, byteCount: Int, initialCrc: Int): Int {
        var crc = initialCrc
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            val chunkSize = minOf(remaining, DiskQueueConstants.SCAN_CHUNK_SIZE.toLong())
            crc = Crc32.update(crc, source.readByteArray(chunkSize))
            remaining -= chunkSize
        }
        return crc
    }
}
