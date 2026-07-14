package com.ghostserializer.sync.queue

import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.indexOfSubarray
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers [DiskQueueCompactor] — reclaiming dead space once the dead-byte ratio crosses
 * [DiskQueueConstants.COMPACTION_DEAD_RATIO_THRESHOLD], and that ids/sequence numbers survive it. */
class DiskQueueCompactionTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("ghost-sync-compaction-test")
        queuePath = (dir.toString() + "/queue.bin").toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `compaction shrinks the file once dead bytes cross the threshold and preserves live entries and ids`() =
        runBlocking {
            val queue = DiskQueue(queuePath)
            val ids = (1..10).map { i -> queue.enqueue("POST", "/item-$i", FrozenHttpHeaders.EMPTY, "payload-$i".encodeToByteArray()) }

            val survivor = ids.last()
            ids.dropLast(1).forEach { queue.remove(it) }

            val sizeAfterCompaction = FileSystem.SYSTEM.metadata(queuePath).size!!
            val remaining = queue.peekAll()

            assertEquals(1, remaining.size)
            assertEquals("/item-10", remaining[0].meta.url)
            assertEquals(survivor, remaining[0].id)
            // Ten records compact down to one; the file must shrink well below its ten-record peak.
            assertTrue(sizeAfterCompaction < 300)

            val reopened = DiskQueue(queuePath)
            assertEquals("/item-10", reopened.peek()?.meta?.url)

            // The id survives compaction: removing it by the same id issued before compaction must work.
            reopened.remove(survivor)
            assertTrue(reopened.isEmpty())
        }

    @Test
    fun `sequence id is preserved and monotonic after out-of-order removal compaction and restart`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val ids = (0..10).map { queue.enqueue("POST", "/$it", FrozenHttpHeaders.EMPTY, ByteArray(0)) }

        // Remove all except the first one, including the last one.
        (1..10).forEach { queue.remove(ids[it]) }

        // Check nextSequenceId by reopening and enqueuing a new item.
        val reopened = DiskQueue(queuePath)
        val newId = reopened.enqueue("POST", "/new", FrozenHttpHeaders.EMPTY, ByteArray(0))

        // The new ID sequenceId must be strictly greater than the maximum sequenceId previously assigned (which was ids.last().sequenceId = 10, so newId must be 11 or greater).
        assertTrue(newId.sequenceId > ids.last().sequenceId, "newId sequence ID should be greater than previous max")
    }

    @Test
    fun `peek tombstoning corrupt heads triggers compaction when dead ratio is high enough`() = runBlocking {
        val queue = DiskQueue(queuePath)
        repeat(10) { index ->
            queue.enqueue("POST", "/item-$index", FrozenHttpHeaders.EMPTY, "payload-$index".encodeToByteArray())
        }

        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        for (index in 0 until 9) {
            val marker = "payload-$index".encodeToByteArray()
            val bodyStart = bytes.indexOfSubarray(marker)
            bytes[bodyStart] = (bytes[bodyStart] + 1).toByte()
        }
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }
        queue.syncMetadataAfterExternalFileChange()

        // Keep the same open instance so the in-memory index still references the corrupt
        // records; a fresh reopen would rebuild the index from recovery and skip them entirely.
        val sizeBefore = FileSystem.SYSTEM.metadata(queuePath).size!!
        val peeked = queue.peek()
        assertEquals("/item-9", peeked?.meta?.url)

        val sizeAfter = FileSystem.SYSTEM.metadata(queuePath).size!!
        assertTrue(sizeAfter < sizeBefore, "expected compaction to shrink $sizeBefore -> $sizeAfter")
        assertEquals(1, queue.size())
    }

    /** After tests corrupt the queue file on disk, prevent [DiskQueue]'s mtime-based rescan from
     * rebuilding the index and skipping the corrupt entries this test expects [peek] to tombstone. */
    private fun DiskQueue.syncMetadataAfterExternalFileChange() {
        val field = DiskQueue::class.java.getDeclaredField("lastKnownDiskModifiedAtMillis").apply {
            isAccessible = true
        }
        if (FileSystem.SYSTEM.exists(path)) {
            field.set(this, FileSystem.SYSTEM.metadata(path).lastModifiedAtMillis)
        }
    }
}
