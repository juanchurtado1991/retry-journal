package com.ghostserializer.sync.queue.record

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.FrozenHttpRequestMeta
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecordCodecTest {

    private val metaBytes = Ghost.encodeToBytes(
        FrozenHttpRequestMeta(
            method = "POST",
            url = "https://example.com/a",
            headers = FrozenHttpHeaders.of("X" to "1"),
            enqueuedAtMillis = 42L,
        ),
    )
    private val body = "hello".encodeToByteArray()

    @Test
    fun `writeLive and readRecord round-trip a live record`() {
        val buffer = Buffer()
        val written = RecordCodec.writeLive(buffer, sequenceId = 7L, metaBytes, body)
        val result = RecordCodec.readRecord(buffer, DiskQueueConstants.MAX_RECORD_FIELD_SIZE)
        val live = assertIs<RecordReadResult.Live>(result)
        assertEquals(7L, live.sequenceId)
        assertEquals(written, live.recordLength)
        assertTrue(live.body.contentEquals(body))
        assertEquals(live, live.copy())
        assertTrue(live.toString().contains("body.size=5"))
    }

    @Test
    fun `writeTombstone and readRecord round-trip`() {
        val buffer = Buffer()
        RecordCodec.writeTombstone(buffer, targetSequenceId = 3L)
        val result = RecordCodec.readRecord(buffer, DiskQueueConstants.MAX_RECORD_FIELD_SIZE)
        val tombstone = assertIs<RecordReadResult.Tombstone>(result)
        assertEquals(3L, tombstone.targetSequenceId)
        assertEquals(DiskQueueConstants.TOMBSTONE_RECORD_SIZE, tombstone.recordLength)
    }

    @Test
    fun `readRecord returns EndOfFile on empty source`() {
        val result = RecordCodec.readRecord(Buffer(), DiskQueueConstants.MAX_RECORD_FIELD_SIZE)
        assertEquals(RecordReadResult.EndOfFile, result)
    }

    @Test
    fun `readRecord returns Invalid for unknown kind byte`() {
        val buffer = Buffer().writeByte(99)
        assertEquals(RecordReadResult.Invalid, RecordCodec.readRecord(buffer, DiskQueueConstants.MAX_RECORD_FIELD_SIZE))
    }

    @Test
    fun `readRecord returns Invalid when live CRC does not match`() {
        val buffer = Buffer()
        RecordCodec.writeLive(buffer, sequenceId = 1L, metaBytes, body)
        val bytes = buffer.readByteArray()
        bytes[DiskQueueConstants.KIND_FIELD_SIZE + 1] = (bytes[5].toInt() xor 0xFF).toByte()
        val corrupt = Buffer().write(bytes)
        assertEquals(RecordReadResult.Invalid, RecordCodec.readRecord(corrupt, DiskQueueConstants.MAX_RECORD_FIELD_SIZE))
    }

    @Test
    fun `readRecord returns Invalid when meta length exceeds maxRecordFieldSize`() {
        val buffer = Buffer()
        buffer.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
        buffer.writeInt(0)
        buffer.writeLong(1L)
        buffer.writeInt(DiskQueueConstants.MAX_RECORD_FIELD_SIZE + 1)
        assertEquals(RecordReadResult.Invalid, RecordCodec.readRecord(buffer, DiskQueueConstants.MAX_RECORD_FIELD_SIZE))
    }
}
