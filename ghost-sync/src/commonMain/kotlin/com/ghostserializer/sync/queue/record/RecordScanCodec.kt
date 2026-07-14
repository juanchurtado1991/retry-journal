package com.ghostserializer.sync.queue.record

import com.ghostserializer.sync.queue.disk.DiskQueueConstants
import okio.BufferedSource
import okio.EOFException

/**
 * Same on-disk framing and the same full CRC verification as [RecordCodec.readRecord], but for
 * [DiskQueue][com.ghostserializer.sync.queue.disk.DiskQueue]'s crash-recovery scan: it only ever needs
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
        resetScanResult(outResult)

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

    private fun resetScanResult(outResult: RecordScanResult) {
        // outResult is reused across every call for one recover() pass — reset recordLength here so
        // a TYPE_INVALID branch that returns without setting it (unrecognized kind byte, or
        // scanLivePayload's metaLen/bodyLen range checks) can never leak the previous record's
        // length into DiskQueueRecovery's advance-by-recordLength logic.
        outResult.recordLength = 0
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

        val recordLength = liveRecordLength(metaLen, bodyLen)
        if (Crc32.finalize(crc) != expectedCrc) {
            outResult.type = RecordScanResult.TYPE_INVALID
            outResult.recordLength = recordLength
            return
        }

        outResult.type = RecordScanResult.TYPE_LIVE
        outResult.sequenceId = sequenceId
        outResult.recordLength = recordLength
    }

    private fun liveRecordLength(metaLen: Int, bodyLen: Int): Int =
        DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaLen +
            DiskQueueConstants.LENGTH_FIELD_SIZE + bodyLen

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
            readFullyIntoBuffer(source, scanBuffer, chunkSize)
            crc = Crc32.update(crc, scanBuffer, 0, chunkSize)
            remaining -= chunkSize
        }
        return crc
    }

    private fun readFullyIntoBuffer(source: BufferedSource, scanBuffer: ByteArray, chunkSize: Int) {
        var read = 0
        while (read < chunkSize) {
            val next = source.read(scanBuffer, read, chunkSize - read)
            if (next == -1) throw EOFException(DiskQueueConstants.RECORD_EOF_PREMATURE_MESSAGE)
            read += next
        }
    }
}
