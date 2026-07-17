package com.retryjournal.queue.disk

import com.ghost.serialization.Ghost
import com.retryjournal.freshTestDir
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import com.retryjournal.queue.record.PackedIndexEntry
import com.retryjournal.queue.record.RecordCodec
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [DiskQueueCompactor] trusts [LiveEntryIndex] to accurately describe what's on disk — these
 * tests drive it directly with a hand-crafted mismatch between the two, something that should
 * never happen through [DiskQueue]'s own public API but which the compactor must still not
 * silently paper over if it ever does. */
class DiskQueueCompactorTest {

    private lateinit var path: Path

    @BeforeTest
    fun setUp() {
        path = freshTestDir("retry-journal-compactor-test").resolve("queue.bin")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(path.parent!!, mustExist = false)
    }

    private fun metaBytes(url: String) = Ghost.encodeToBytes(
        FrozenHttpRequestMeta("POST", url, FrozenHttpHeaders.EMPTY, enqueuedAtMillis = 0L),
    )

    /** Regression: a sequence id the index claims is live, whose recorded offset actually lands
     * on a tombstone (or anything else that isn't a matching [com.retryjournal.queue.record.RecordReadResult.Live])
     * used to be handled asymmetrically from a live-record-with-the-wrong-sequence-id: the latter
     * safely aborted compaction, but this one silently wrote a fresh tombstone for the claimed
     * sequence id and kept going — dropping the entry from the compacted file without a trace. */
    @Test
    fun `planCompaction aborts when the index points at an offset that is not a live record at all`() {
        var tombstoneOffset = 0L
        var keptWritten = 0
        FileSystem.SYSTEM.sink(path).buffer().use { sink ->
            keptWritten = RecordCodec.writeLive(sink, 0L, metaBytes("/kept"), "kept-body".encodeToByteArray())
            sink.flush()
            tombstoneOffset = keptWritten.toLong()
            RecordCodec.writeTombstone(sink, 99L)
        }

        val index = LiveEntryIndex()
        index[0L] = PackedIndexEntry.pack(keptWritten, 0L)
        // Sequence id 1 is claimed live, but its recorded offset actually lands on the tombstone
        // written above, not a Live record for sequence id 1 — an inconsistency that should never
        // arise through DiskQueue's own API, but must not be silently dropped if it ever does.
        index[1L] = PackedIndexEntry.pack(DiskQueueConstants.TOMBSTONE_RECORD_SIZE, tombstoneOffset)

        val fileLength = FileSystem.SYSTEM.metadata(path).size!!
        val plan = DiskQueueCompactor.planCompaction(
            fileSystem = FileSystem.SYSTEM,
            path = path,
            maxRecordFieldSize = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            liveOffsetsBySequence = index,
            fileLength = fileLength,
            deadBytes = (fileLength * 9) / 10, // force shouldCompact() to be true
            nextSequenceId = 2L,
        )

        assertNull(plan, "an index/disk mismatch must abort compaction, not silently drop the entry")
    }

    /** Sanity check that [planCompaction] does proceed for a fully consistent index, so the test
     * above is verifying the mismatch case specifically and not just "always returns null". */
    @Test
    fun `planCompaction proceeds normally when the index matches the disk`() {
        var keptWritten = 0
        FileSystem.SYSTEM.sink(path).buffer().use { sink ->
            keptWritten = RecordCodec.writeLive(sink, 0L, metaBytes("/kept"), "kept-body".encodeToByteArray())
        }

        val index = LiveEntryIndex()
        index[0L] = PackedIndexEntry.pack(keptWritten, 0L)

        val fileLength = FileSystem.SYSTEM.metadata(path).size!!
        val plan = DiskQueueCompactor.planCompaction(
            fileSystem = FileSystem.SYSTEM,
            path = path,
            maxRecordFieldSize = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
            liveOffsetsBySequence = index,
            fileLength = fileLength,
            deadBytes = (fileLength * 9) / 10,
            nextSequenceId = 1L,
        )

        assertNotNull(plan)
    }
}
