package com.retryjournal.queue

import com.ghost.serialization.Ghost
import com.retryjournal.freshTestDir
import com.retryjournal.indexOfSubarray
import com.retryjournal.peekAll
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.record.RecordCodec
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers [com.retryjournal.queue.disk.DiskQueueRecovery] — the crash-recovery scan [com.retryjournal.queue.disk.DiskQueue] runs the first time it opens
 * a queue file, rebuilding the live/tombstone index and dropping anything corrupt or truncated. */
class DiskQueueRecoveryTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        queuePath = freshTestDir("retry-journal-recovery-test").resolve("queue.bin")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `reopening after an abrupt cut recovers every complete record and drops the partial tail`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())

        // Simulate a process kill mid-write of a third record: its header promises a 50-byte
        // body but only 10 bytes ever made it to disk before the process died. The two prior
        // records are untouched.
        FileSystem.SYSTEM.appendingSink(queuePath).buffer().use { sink ->
            sink.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
            sink.writeInt(0x1234)
            sink.writeLong(999L)
            sink.writeInt(50)
            sink.write(ByteArray(10))
        }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        assertEquals(2, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertEquals("/second", entries[1].meta.url)

        // The recovered queue must still be writable — the corrupt tail was truncated, not just skipped.
        reopened.enqueue("POST", "/third", FrozenHttpHeaders.EMPTY, "third-body".encodeToByteArray())
        assertEquals(3, reopened.peekAll().size)
    }

    @Test
    fun `the fast recovery scan still detects a corrupted body not just a truncated one`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val idB = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())

        // Flip a byte inside "/second"'s body on disk directly — a full record is present (no
        // truncation), but its CRC no longer matches. The fast scan (RecordScanCodec.scanRecord)
        // hashes bodies in chunks without materializing them; this proves that path still
        // verifies every byte instead of trusting the length fields alone.
        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        val marker = "second-body".encodeToByteArray()
        val bodyStart = bytes.indexOfSubarray(marker)
        bytes[bodyStart] = (bytes[bodyStart] + 1).toByte()
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        // The corrupted record and anything after it is dropped by truncation; "/first" survives.
        assertEquals(1, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertNull(reopened.get(idB))
    }

    @Test
    fun `isolated corruption in the middle is skipped and subsequent valid records are recovered`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())
        queue.enqueue("POST", "/third", FrozenHttpHeaders.EMPTY, "third-body".encodeToByteArray())

        // Corrupt "/second"'s record bytes.
        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        val marker = "second-body".encodeToByteArray()
        val bodyStart = bytes.indexOfSubarray(marker)
        bytes[bodyStart] = (bytes[bodyStart] + 1).toByte()
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        // "/first" and "/third" must be recovered, "/second" (corrupted) must be skipped.
        assertEquals(2, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertEquals("/third", entries[1].meta.url)
    }

    @Test
    fun `corrupted tombstone is skipped by its own size not a stale length from the record before it`() = runBlocking {
        // Hand-crafted directly with RecordCodec, bypassing DiskQueue.remove()'s own compaction
        // entirely (a big-bodied record's tombstone alone can cross the dead-ratio threshold and
        // rewrite the file), so the byte layout below is exactly what ends up on disk.
        fun metaBytes(url: String) = Ghost.encodeToBytes(
            FrozenHttpRequestMeta("POST", url, FrozenHttpHeaders.EMPTY, enqueuedAtMillis = 0L),
        )

        var tombstoneStart = 0L
        FileSystem.SYSTEM.appendingSink(queuePath).buffer().use { sink ->
            RecordCodec.writeLive(sink, 0L, metaBytes("/first"), "first-body".encodeToByteArray())
            sink.flush()

            // Deliberately much bigger than TOMBSTONE_RECORD_SIZE (13 bytes): RecordScanResult is
            // a reused mutable carrier, so if the scan mistakenly kept this record's length
            // around instead of setting the tombstone's own size for the corrupted tombstone
            // right after it, it would skip far past "/third" instead of landing on it.
            RecordCodec.writeLive(sink, 1L, metaBytes("/second"), ByteArray(500))
            sink.flush()
            tombstoneStart = FileSystem.SYSTEM.metadata(queuePath).size!!

            RecordCodec.writeTombstone(sink, 1L)
            sink.flush()

            RecordCodec.writeLive(sink, 2L, metaBytes("/third"), "third-body".encodeToByteArray())
        }

        // Corrupt the tombstone's CRC — 4 bytes right after its 1-byte kind marker.
        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        val crcOffset = (tombstoneStart + 1).toInt()
        bytes[crcOffset] = (bytes[crcOffset] + 1).toByte()
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }

        // "/second" is *not* removed — a tombstone whose own CRC fails can't be trusted to
        // delete anything, so recovery keeps the record it would have removed. What this test
        // actually guards is "/third": with the bug, the scanner's stale, oversized skip distance
        // overshoots past end-of-file in one jump, the scan loop exits immediately, and
        // truncateToLocked() then physically deletes both the tombstone and "/third" from disk.
        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        assertEquals(3, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertEquals("/second", entries[1].meta.url)
        assertEquals("/third", entries[2].meta.url)
    }

    @Test
    fun `corrupted metaLen on a live record is skipped by byte-by-byte resync not a stale length from the record before it`() = runBlocking {
        // Same failure class as the corrupted-tombstone test above, but for the gap the reporter
        // pointed out that test doesn't cover: a *live* record whose own metaLen is corrupted,
        // right after another live record — RecordScanResult's reused recordLength field must not
        // leak the previous record's length into this record's TYPE_INVALID result.
        fun metaBytes(url: String) = Ghost.encodeToBytes(
            FrozenHttpRequestMeta("POST", url, FrozenHttpHeaders.EMPTY, enqueuedAtMillis = 0L),
        )

        FileSystem.SYSTEM.appendingSink(queuePath).buffer().use { sink ->
            // Deliberately much bigger than the corrupted record's own 17-byte header (kind +
            // crc + sequenceId + metaLen): if the scan mistakenly kept this record's length
            // around instead of resetting it for the corrupted metaLen right after, it would
            // skip far past "/third" instead of landing on it.
            RecordCodec.writeLive(sink, 0L, metaBytes("/first"), ByteArray(500))
            sink.flush()

            // Hand-written, not via RecordCodec: a live record whose metaLen field is corrupted
            // out of range. Never read past metaLen — meta/body bytes for this "record" don't
            // exist on disk at all. Every filler byte after the kind byte is 0xFF (-1) on
            // purpose: RECORD_KIND_LIVE_BYTE/RECORD_KIND_TOMBSTONE_BYTE are 1 and 2, so the
            // byte-by-byte resync that follows can't mistake one of these filler bytes for
            // another record's kind byte and go chasing a bogus length off the end of the file.
            sink.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
            sink.writeInt(-1) // crc — never checked; the metaLen range check fails first
            sink.writeLong(-1L)
            sink.writeInt(-1) // corrupted metaLen
            sink.flush()

            RecordCodec.writeLive(sink, 2L, metaBytes("/third"), "third-body".encodeToByteArray())
        }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        // "/first" and "/third" must both be recovered; the corrupted 17-byte record between
        // them is skipped a byte at a time, not jumped over (or past) using "/first"'s length.
        assertEquals(2, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertEquals("/third", entries[1].meta.url)
    }

    @Test
    fun `corrupted bodyLen still in range does not skip past valid records that follow`() = runBlocking {
        // Regression: on a live record's CRC mismatch, the scanner used to still report its own
        // computed recordLength (derived from metaLen/bodyLen) for DiskQueueRecovery to skip by —
        // trusted even though those fields are exactly what's unverified when the CRC fails. A
        // corrupted bodyLen that's still in-range (so the earlier range check doesn't catch it)
        // makes the scanner hash the wrong number of body bytes, desyncing the stream from the
        // file's true record boundaries. Jumping by that wrong length can overshoot end-of-file,
        // and truncateTrailingInvalidBytes then discards everything from there on — including
        // "/third" below, which was never actually corrupted.
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val secondBody = "second-body-0123456789"
        queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, secondBody.encodeToByteArray())
        queue.enqueue("POST", "/third", FrozenHttpHeaders.EMPTY, "third-body".encodeToByteArray())

        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        val bodyStart = bytes.indexOfSubarray(secondBody.encodeToByteArray())
        val bodyLenFieldStart = bodyStart - 4
        val original = ((bytes[bodyLenFieldStart].toInt() and 0xFF) shl 24) or
            ((bytes[bodyLenFieldStart + 1].toInt() and 0xFF) shl 16) or
            ((bytes[bodyLenFieldStart + 2].toInt() and 0xFF) shl 8) or
            (bytes[bodyLenFieldStart + 3].toInt() and 0xFF)
        // One byte shorter — still comfortably in range (0..maxRecordFieldSize), but wrong.
        val corrupted = original - 1
        bytes[bodyLenFieldStart] = (corrupted ushr 24).toByte()
        bytes[bodyLenFieldStart + 1] = (corrupted ushr 16).toByte()
        bytes[bodyLenFieldStart + 2] = (corrupted ushr 8).toByte()
        bytes[bodyLenFieldStart + 3] = corrupted.toByte()
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()

        assertEquals(2, entries.size)
        assertEquals("/first", entries[0].meta.url)
        assertEquals("/third", entries[1].meta.url)
    }

    @Test
    fun `scanning a truncated record at the end does not perform a slow byte-by-byte scan`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())

        FileSystem.SYSTEM.appendingSink(queuePath).buffer().use { sink ->
            sink.writeByte(DiskQueueConstants.RECORD_KIND_LIVE_INT)
            sink.writeInt(0x1234) // crc
            sink.writeLong(999L) // seq
            sink.writeInt(10_000) // meta length
            sink.write(ByteArray(5)) // write only 5 bytes
        }

        val reopened = DiskQueue(queuePath)
        val entries = reopened.peekAll()
        assertEquals(1, entries.size)
        assertEquals("/first", entries[0].meta.url)
    }
}
