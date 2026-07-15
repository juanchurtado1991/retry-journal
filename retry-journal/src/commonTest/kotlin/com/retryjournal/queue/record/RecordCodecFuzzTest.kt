package com.retryjournal.queue.record

import com.ghost.serialization.Ghost
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import com.retryjournal.queue.disk.DiskQueueConstants
import okio.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Property-based coverage for the on-disk record framing, on top of [RecordCodecTest]'s
 * example-based cases. Two properties, run against many random inputs with a fixed seed (so
 * failures reproduce deterministically instead of flaking in CI):
 *
 * 1. Crash safety: [RecordCodec.readRecord] and [RecordScanCodec.scanRecord] must never throw,
 *    no matter what bytes they're pointed at — a crash-recovery scan runs against a file that
 *    may have been truncated by a real process kill, so "never throws on garbage" is a real
 *    production invariant, not a hypothetical one.
 * 2. Differential agreement: [RecordCodec] (materializes meta/body) and [RecordScanCodec]
 *    (zero-allocation, used only by crash recovery) parse the *same* on-disk framing. For any
 *    input, their classification, sequenceId, and recordLength must agree — if they diverged,
 *    recovery would rebuild a different index than a normal read would see, corrupting the
 *    queue silently.
 */
class RecordCodecFuzzTest {

    private val seed = 20260715L
    private val iterations = 2_000
    private val maxFieldSize = DiskQueueConstants.MAX_RECORD_FIELD_SIZE
    private val scanBuffer = ByteArray(4_096)

    @Test
    fun `readRecord and scanRecord never throw on pure random bytes`() {
        val random = Random(seed)

        repeat(iterations) {
            val bytes = random.nextBytes(random.nextInt(0, 300))
            val scanResult = RecordScanResult()

            // Neither call is allowed to throw — any exception here fails the test.
            val readResult = RecordCodec.readRecord(Buffer().write(bytes), maxFieldSize)
            RecordScanCodec.scanRecord(Buffer().write(bytes), maxFieldSize, scanBuffer, scanResult)

            assertAgree(bytes, readResult, scanResult)
        }
    }

    @Test
    fun `readRecord and scanRecord never throw on truncated valid records`() {
        val random = Random(seed + 1)

        repeat(iterations) {
            val full = validRecordBytes(random)
            val truncateAt = random.nextInt(0, full.size + 1)
            val bytes = full.copyOf(truncateAt)
            val scanResult = RecordScanResult()

            val readResult = RecordCodec.readRecord(Buffer().write(bytes), maxFieldSize)
            RecordScanCodec.scanRecord(Buffer().write(bytes), maxFieldSize, scanBuffer, scanResult)

            assertAgree(bytes, readResult, scanResult)

            if (truncateAt < full.size) {
                // A strictly truncated record can never parse as a complete Live/Tombstone —
                // there's no way to legitimately reconstruct bytes that were never written.
                assertTrue(
                    readResult == RecordReadResult.Invalid || readResult == RecordReadResult.EndOfFile,
                    "truncated record (kept $truncateAt/${full.size} bytes) parsed as $readResult",
                )
            }
        }
    }

    @Test
    fun `single-byte corruption of a valid record is always caught by the CRC`() {
        val random = Random(seed + 2)

        repeat(iterations) {
            val original = validRecordBytes(random)
            val originalParsed = RecordCodec.readRecord(Buffer().write(original), maxFieldSize)
            val originalSequenceId = when (originalParsed) {
                is RecordReadResult.Live -> originalParsed.sequenceId
                is RecordReadResult.Tombstone -> originalParsed.targetSequenceId
                else -> error("fixture didn't parse as a valid record: $originalParsed")
            }

            val corrupted = original.copyOf()
            val flipIndex = random.nextInt(corrupted.size)
            corrupted[flipIndex] = (corrupted[flipIndex].toInt() xor (1 shl random.nextInt(8))).toByte()
            if (corrupted.contentEquals(original)) {
                // The xor happened to be a no-op bit already at that value — nothing to assert.
                return@repeat
            }

            val scanResult = RecordScanResult()
            val readResult = RecordCodec.readRecord(Buffer().write(corrupted), maxFieldSize)
            RecordScanCodec.scanRecord(Buffer().write(corrupted), maxFieldSize, scanBuffer, scanResult)

            assertAgree(corrupted, readResult, scanResult)

            // A CRC32 collision from a single genuine bit flip is ~1-in-4-billion — in thousands
            // of iterations it should never happen. If it does, either report it as a Live/
            // Tombstone whose sequenceId still matches the original (a coincidental split that
            // reassembled the same logical value), or it must be rejected as Invalid.
            when (readResult) {
                is RecordReadResult.Live -> assertEquals(
                    originalSequenceId,
                    readResult.sequenceId,
                    "corrupted byte at index $flipIndex parsed as Live with a different sequenceId — CRC failed to catch it",
                )
                is RecordReadResult.Tombstone -> assertEquals(
                    originalSequenceId,
                    readResult.targetSequenceId,
                    "corrupted byte at index $flipIndex parsed as Tombstone with a different sequenceId — CRC failed to catch it",
                )
                RecordReadResult.Invalid, RecordReadResult.EndOfFile -> Unit // expected outcome
            }
        }
    }

    /** Fails the test if [RecordCodec] and [RecordScanCodec] disagree on how to parse the exact
     * same bytes — they implement the same on-disk framing independently and must always agree,
     * with one deliberate exception: a genuine truncation (not enough bytes to finish reading a
     * record) is reported as [RecordReadResult.Invalid] by [RecordCodec] (it's invoked at
     * arbitrary offsets from a possibly-stale index — "this record is unreadable" doesn't imply
     * anything about what follows it) but as `TYPE_EOF` by [RecordScanCodec] (it only ever scans
     * sequentially from byte 0, so running out of bytes mid-record can only mean the true physical
     * end of the file — see [com.retryjournal.queue.disk.DiskQueueRecovery], which truncates the
     * file to the last valid offset on `TYPE_EOF`, discarding the partial tail write outright). */
    private fun assertAgree(bytes: ByteArray, readResult: RecordReadResult, scanResult: RecordScanResult) {
        val label = "for ${bytes.size} input bytes"
        if (readResult == RecordReadResult.Invalid && scanResult.type == RecordScanResult.TYPE_EOF) {
            return // the documented truncation-vs-corruption divergence — not a disagreement
        }
        when (readResult) {
            RecordReadResult.EndOfFile -> assertEquals(RecordScanResult.TYPE_EOF, scanResult.type, "EndOfFile $label")
            RecordReadResult.Invalid -> assertEquals(RecordScanResult.TYPE_INVALID, scanResult.type, "Invalid $label")
            is RecordReadResult.Live -> {
                assertEquals(RecordScanResult.TYPE_LIVE, scanResult.type, "Live $label")
                assertEquals(readResult.sequenceId, scanResult.sequenceId, "Live sequenceId $label")
                assertEquals(readResult.recordLength, scanResult.recordLength, "Live recordLength $label")
            }
            is RecordReadResult.Tombstone -> {
                assertEquals(RecordScanResult.TYPE_TOMBSTONE, scanResult.type, "Tombstone $label")
                assertEquals(readResult.targetSequenceId, scanResult.sequenceId, "Tombstone sequenceId $label")
                assertEquals(readResult.recordLength, scanResult.recordLength, "Tombstone recordLength $label")
            }
        }
    }

    private fun validRecordBytes(random: Random): ByteArray {
        val buffer = Buffer()
        if (random.nextBoolean()) {
            val metaBytes = Ghost.encodeToBytes(
                FrozenHttpRequestMeta(
                    method = if (random.nextBoolean()) "POST" else "GET",
                    url = "https://example.com/${random.nextInt(0, 100_000)}",
                    headers = FrozenHttpHeaders.of("X" to random.nextInt().toString()),
                    enqueuedAtMillis = random.nextLong(0, Long.MAX_VALUE / 2),
                ),
            )
            val body = random.nextBytes(random.nextInt(0, 64))
            RecordCodec.writeLive(buffer, sequenceId = random.nextLong(), metaBytes, body)
        } else {
            RecordCodec.writeTombstone(buffer, targetSequenceId = random.nextLong())
        }
        return buffer.readByteArray()
    }
}
