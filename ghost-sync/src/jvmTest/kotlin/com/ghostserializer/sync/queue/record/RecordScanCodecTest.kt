package com.ghostserializer.sync.queue.record

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.FrozenHttpRequestMeta
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordScanCodecTest {

    private val metaBytes = Ghost.encodeToBytes(
        FrozenHttpRequestMeta(
            method = "GET",
            url = "https://example.com/scan",
            headers = FrozenHttpHeaders.EMPTY,
            enqueuedAtMillis = 0L,
        ),
    )

    @Test
    fun `scanRecord detects a valid live record without allocating meta or body`() {
        val writeBuffer = Buffer()
        RecordCodec.writeLive(writeBuffer, sequenceId = 5L, metaBytes, "x".encodeToByteArray())
        val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
        val out = RecordScanResult()
        RecordScanCodec.scanRecord(writeBuffer, DiskQueueConstants.MAX_RECORD_FIELD_SIZE, scanBuffer, out)
        assertEquals(RecordScanResult.TYPE_LIVE, out.type)
        assertEquals(5L, out.sequenceId)
        assertTrue(out.recordLength > 0)
    }

    @Test
    fun `scanRecord detects tombstones`() {
        val writeBuffer = Buffer()
        RecordCodec.writeTombstone(writeBuffer, targetSequenceId = 2L)
        val out = RecordScanResult()
        RecordScanCodec.scanRecord(
            writeBuffer,
            DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE),
            out,
        )
        assertEquals(RecordScanResult.TYPE_TOMBSTONE, out.type)
        assertEquals(2L, out.sequenceId)
    }

    @Test
    fun `scanRecord marks invalid meta length without leaking prior recordLength`() {
        val buffer = Buffer()
        buffer.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
        buffer.writeInt(0)
        buffer.writeLong(1L)
        buffer.writeInt(DiskQueueConstants.MAX_RECORD_FIELD_SIZE + 1)
        val out = RecordScanResult().apply { recordLength = 999 }
        RecordScanCodec.scanRecord(
            buffer,
            DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE),
            out,
        )
        assertEquals(RecordScanResult.TYPE_INVALID, out.type)
        assertEquals(0, out.recordLength)
    }
}
