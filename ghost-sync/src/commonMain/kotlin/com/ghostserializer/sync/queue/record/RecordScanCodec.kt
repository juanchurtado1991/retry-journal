package com.ghostserializer.sync.queue.record

import com.ghostserializer.sync.queue.DiskQueueConstants
import okio.BufferedSource
import okio.EOFException

/**
 * Same on-disk framing and the same full CRC verification as [RecordCodec.readRecord], but for
 * [DiskQueue][com.ghostserializer.sync.queue.DiskQueue]'s crash-recovery scan: it only ever needs
 * [RecordScanResult]'s sequence id and record length to rebuild the offset index, so meta/body
 * bytes are hashed in bounded chunks ([DiskQueueConstants.SCAN_CHUNK_SIZE]) instead of read into
 * one allocation the size of the field — a queue full of large file/image bodies shouldn't need
 * gigabytes of heap just to open.
 */
internal object RecordScanCodec {

    fun scanRecord(
        source: BufferedSource,
        maxRecordFieldSize: Int,
        scanBuffer: ByteArray,
        outResult: RecordScanResult
    ) {
        if (source.exhausted()) {
            outResult.type = RecordScanResult.TYPE_EOF
            return
        }

        try {
            val kindByte = source.readByte()
            val expectedCrc = source.readInt()

            when (kindByte) {
                DiskQueueConstants.RECORD_KIND_LIVE_BYTE -> {
                    scanLivePayload(
                        source,
                        expectedCrc,
                        maxRecordFieldSize,
                        scanBuffer,
                        outResult
                    )
                }

                DiskQueueConstants.RECORD_KIND_TOMBSTONE_BYTE -> {
                    scanTombstonePayload(source, expectedCrc, outResult)
                }

                else -> {
                    outResult.type = RecordScanResult.TYPE_INVALID
                }
            }
        } catch (_: EOFException) {
            outResult.type = RecordScanResult.TYPE_EOF
        }
    }

    private fun scanLivePayload(
        source: BufferedSource,
        expectedCrc: Int,
        maxRecordFieldSize: Int,
        scanBuffer: ByteArray,
        outResult: RecordScanResult
    ) {
        val sequenceId = source.readLong()

        val metaLen = source.readInt()
        if (metaLen !in 0..maxRecordFieldSize) {
            outResult.type = RecordScanResult.TYPE_INVALID
            return
        }
        var crc = Crc32.updateLong(Crc32.INITIAL_VALUE, sequenceId)
        crc = hashChunked(source, metaLen, crc, scanBuffer)

        val bodyLen = source.readInt()
        if (bodyLen !in 0..maxRecordFieldSize) {
            outResult.type = RecordScanResult.TYPE_INVALID
            return
        }
        crc = hashChunked(source, bodyLen, crc, scanBuffer)

        val recordLength = DiskQueueConstants.RECORD_HEADER_SIZE +
                DiskQueueConstants.SEQUENCE_FIELD_SIZE +
                DiskQueueConstants.LENGTH_FIELD_SIZE + metaLen +
                DiskQueueConstants.LENGTH_FIELD_SIZE + bodyLen

        if (Crc32.finalize(crc) != expectedCrc) {
            outResult.type = RecordScanResult.TYPE_INVALID
            outResult.recordLength = recordLength
            return
        }

        outResult.type = RecordScanResult.TYPE_LIVE
        outResult.sequenceId = sequenceId
        outResult.recordLength = recordLength
    }

    private fun scanTombstonePayload(
        source: BufferedSource,
        expectedCrc: Int,
        outResult: RecordScanResult
    ) {
        val targetSequenceId = source.readLong()
        val crc = Crc32.finalize(
            Crc32.updateLong(
                Crc32.INITIAL_VALUE,
                value = targetSequenceId
            )
        )
        if (crc != expectedCrc) {
            outResult.type = RecordScanResult.TYPE_INVALID
            outResult.recordLength = DiskQueueConstants.TOMBSTONE_RECORD_SIZE
            return
        }
        outResult.type = RecordScanResult.TYPE_TOMBSTONE
        outResult.sequenceId = targetSequenceId
        outResult.recordLength = DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    private fun hashChunked(
        source: BufferedSource,
        byteCount: Int,
        initialCrc: Int,
        scanBuffer: ByteArray
    ): Int {
        var crc = initialCrc
        var remaining = byteCount.toLong()
        while (remaining > 0) {
            val chunkSize = minOf(remaining, scanBuffer.size.toLong()).toInt()
            var read = 0
            while (read < chunkSize) {
                val next = source.read(scanBuffer, read, chunkSize - read)
                if (next == -1) throw EOFException(DiskQueueConstants.RECORD_EOF_PREMATURE_MESSAGE)
                read += next
            }
            crc = Crc32.update(crc, scanBuffer, 0, chunkSize)
            remaining -= chunkSize
        }
        return crc
    }
}
