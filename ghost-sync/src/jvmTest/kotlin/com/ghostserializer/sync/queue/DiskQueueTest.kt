package com.ghostserializer.sync.queue

import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskQueueTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        val dir = Files.createTempDirectory("ghost-sync-test")
        queuePath = (dir.toString() + "/queue.bin").toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `enqueue then peek returns the same request without consuming it`() = runBlocking {
        val queue = DiskQueue(queuePath)

        queue.enqueue(
            method = "POST",
            url = "https://example.com/a",
            headers = mapOf("Content-Type" to "application/json"),
            body = "body-a".encodeToByteArray(),
        )

        val first = queue.peek()
        val second = queue.peek()

        assertEquals("POST", first?.meta?.method)
        assertEquals("https://example.com/a", first?.meta?.url)
        assertEquals("body-a", first?.body?.decodeToString())
        assertEquals(first?.id, second?.id)
    }

    @Test
    fun `entries are served in FIFO order`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val idA = queue.enqueue("POST", "/a", emptyMap(), "a".encodeToByteArray())
        queue.enqueue("POST", "/b", emptyMap(), "b".encodeToByteArray())
        queue.enqueue("POST", "/c", emptyMap(), "c".encodeToByteArray())

        assertEquals(idA, queue.peek()?.id)
        queue.remove(idA)
        assertEquals("/b", queue.peek()?.meta?.url)
    }

    @Test
    fun `remove is idempotent for unknown or already-removed ids`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("GET", "/x", emptyMap(), ByteArray(0))

        queue.remove(id)
        queue.remove(id)
        queue.remove(QueueEntryId(999_999L))

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a fresh instance sees entries enqueued and removed by a previous instance`() = runBlocking {
        val first = DiskQueue(queuePath)
        val idA = first.enqueue("POST", "/a", emptyMap(), "a".encodeToByteArray())
        first.enqueue("POST", "/b", emptyMap(), "b".encodeToByteArray())
        first.remove(idA)

        val reopened = DiskQueue(queuePath)
        val remaining = reopened.peekAll()

        assertEquals(1, remaining.size)
        assertEquals("/b", remaining[0].meta.url)
    }

    @Test
    fun `reopening after an abrupt cut recovers every complete record and drops the partial tail`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", emptyMap(), "first-body".encodeToByteArray())
        queue.enqueue("POST", "/second", emptyMap(), "second-body".encodeToByteArray())

        // Simulate a process kill mid-write of a third record: its header promises a 50-byte
        // body but only 10 bytes ever made it to disk before the process died. The two prior
        // records are untouched.
        FileSystem.SYSTEM.appendingSink(queuePath).buffer().use { sink ->
            sink.writeByte(RecordKind.Live.byteValue.toInt())
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
        reopened.enqueue("POST", "/third", emptyMap(), "third-body".encodeToByteArray())
        assertEquals(3, reopened.peekAll().size)
    }

    @Test
    fun `size reflects enqueues and removals without decoding any record`() = runBlocking {
        val queue = DiskQueue(queuePath)
        assertEquals(0, queue.size())

        val idA = queue.enqueue("POST", "/a", emptyMap(), "a".encodeToByteArray())
        queue.enqueue("POST", "/b", emptyMap(), "b".encodeToByteArray())
        assertEquals(2, queue.size())

        queue.remove(idA)
        assertEquals(1, queue.size())
    }

    @Test
    fun `peekIds returns the oldest ids first without decoding any record, capped at the limit`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val idA = queue.enqueue("POST", "/a", emptyMap(), "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "/b", emptyMap(), "b".encodeToByteArray())
        queue.enqueue("POST", "/c", emptyMap(), "c".encodeToByteArray())

        assertEquals(listOf(idA, idB), queue.peekIds(limit = 2))
        assertEquals(3, queue.peekIds(limit = 10).size)
    }

    @Test
    fun `an empty queue peeks as null and stays empty after reopening`() = runBlocking {
        val queue = DiskQueue(queuePath)
        assertNull(queue.peek())

        val reopened = DiskQueue(queuePath)
        assertTrue(reopened.isEmpty())
    }

    @Test
    fun `maxRecordFieldSize is configurable per queue instead of a fixed global cap`() = runBlocking {
        val customLimit = 1_024
        val queue = DiskQueue(queuePath, maxRecordFieldSize = customLimit)

        // Under the constructor's own limit: fine.
        queue.enqueue("POST", "/small", emptyMap(), ByteArray(customLimit))

        // One byte past THIS queue's configured limit, even though it's nowhere near the
        // library's 64 MiB default — the configured value is what's actually enforced.
        assertFailsWith<RecordTooLargeException> {
            queue.enqueue("POST", "/big", emptyMap(), ByteArray(customLimit + 1))
        }

        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue rejects a body over the record size limit instead of writing something unreadable`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val oversized = ByteArray(DiskQueueConstants.MAX_RECORD_FIELD_SIZE + 1)

        assertFailsWith<RecordTooLargeException> {
            queue.enqueue("POST", "/big", emptyMap(), oversized)
        }

        // Nothing was written — the queue is still empty, not silently corrupted.
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `the fast recovery scan still detects a corrupted body, not just a truncated one`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", emptyMap(), "first-body".encodeToByteArray())
        val idB = queue.enqueue("POST", "/second", emptyMap(), "second-body".encodeToByteArray())

        // Flip a byte inside "/second"'s body on disk directly — a full record is present (no
        // truncation), but its CRC no longer matches. The fast scan (RecordCodec.scanRecord)
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

    private fun ByteArray.indexOfSubarray(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    continue@outer
                }
            }
            return start
        }
        error("subarray not found")
    }

    @Test
    fun `compaction shrinks the file once dead bytes cross the threshold and preserves live entries and ids`() =
        runBlocking {
            val queue = DiskQueue(queuePath)
            val ids = (1..10).map { i -> queue.enqueue("POST", "/item-$i", emptyMap(), "payload-$i".encodeToByteArray()) }

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
}
