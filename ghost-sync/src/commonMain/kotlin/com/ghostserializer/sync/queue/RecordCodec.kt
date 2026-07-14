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
 *
 * [readRecord] always materializes the meta/body bytes it reads. See [RecordScanCodec] for the
 * zero-allocation variant [DiskQueue]'s crash-recovery scan uses instead.
 */
internal object RecordCodec {

    fun writeLive(
        sink: BufferedSink,
        sequenceId: Long,
        metaBytes: ByteArray,
        body: ByteArray
    ): Int {
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        crc = Crc32.update(crc, body)

        sink.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
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

        sink.writeByte(DiskQueueConstants.RECORD_KIND_TOMBSTONE_INT)
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

    private fun readLivePayload(
        source: BufferedSource,
        expectedCrc: Int,
        maxRecordFieldSize: Int
    ): RecordReadResult {
        val sequenceId = source.readLong()

        val metaLen = source.readInt()
        if (metaLen !in 0..maxRecordFieldSize) {
            return RecordReadResult.Invalid
        }
        val metaBytes = source.readByteArray(metaLen.toLong())

        val bodyLen = source.readInt()
        if (bodyLen !in 0..maxRecordFieldSize) {
            return RecordReadResult.Invalid
        }
        val body = source.readByteArray(bodyLen.toLong())

        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = Crc32.update(crc, metaBytes)
        crc = Crc32.update(crc, body)
        if (Crc32.finalize(crc) != expectedCrc) {
            return RecordReadResult.Invalid
        }

        val meta = try {
            Ghost.deserialize<FrozenHttpRequestMeta>(metaBytes)
        } catch (_: Throwable) {
            return RecordReadResult.Invalid
        }

        val recordLength = DiskQueueConstants.RECORD_HEADER_SIZE +
                DiskQueueConstants.SEQUENCE_FIELD_SIZE +
                DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
                DiskQueueConstants.LENGTH_FIELD_SIZE + body.size

        return RecordReadResult.Live(sequenceId, meta, metaBytes, body, recordLength)
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
