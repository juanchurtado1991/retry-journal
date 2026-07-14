package com.ghostserializer.sync.queue

import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.peekIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.buffer
import java.io.IOException
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
            headers = FrozenHttpHeaders.of("Content-Type" to "application/json"),
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
        val idA = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        queue.enqueue("POST", "/c", FrozenHttpHeaders.EMPTY, "c".encodeToByteArray())

        assertEquals(idA, queue.peek()?.id)
        queue.remove(idA)
        assertEquals("/b", queue.peek()?.meta?.url)
    }

    @Test
    fun `remove is idempotent for unknown or already-removed ids`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("GET", "/x", FrozenHttpHeaders.EMPTY, ByteArray(0))

        queue.remove(id)
        queue.remove(id)
        queue.remove(QueueEntryId(999_999L))

        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a fresh instance sees entries enqueued and removed by a previous instance`() = runBlocking {
        val first = DiskQueue(queuePath)
        val idA = first.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        first.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        first.remove(idA)

        val reopened = DiskQueue(queuePath)
        val remaining = reopened.peekAll()

        assertEquals(1, remaining.size)
        assertEquals("/b", remaining[0].meta.url)
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
    fun `size reflects enqueues and removals without decoding any record`() = runBlocking {
        val queue = DiskQueue(queuePath)
        assertEquals(0, queue.size())

        val idA = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        assertEquals(2, queue.size())

        queue.remove(idA)
        assertEquals(1, queue.size())
    }

    @Test
    fun `peekIds returns the oldest ids first without decoding any record, capped at the limit`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val idA = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        queue.enqueue("POST", "/c", FrozenHttpHeaders.EMPTY, "c".encodeToByteArray())

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
        queue.enqueue("POST", "/small", FrozenHttpHeaders.EMPTY, ByteArray(customLimit))

        // One byte past THIS queue's configured limit, even though it's nowhere near the
        // library's 64 MiB default — the configured value is what's actually enforced.
        assertFailsWith<RecordTooLargeException> {
            queue.enqueue("POST", "/big", FrozenHttpHeaders.EMPTY, ByteArray(customLimit + 1))
        }

        assertEquals(1, queue.size())
    }

    @Test
    fun `enqueue rejects a body over the record size limit instead of writing something unreadable`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val oversized = ByteArray(DiskQueueConstants.MAX_RECORD_FIELD_SIZE + 1)

        assertFailsWith<RecordTooLargeException> {
            queue.enqueue("POST", "/big", FrozenHttpHeaders.EMPTY, oversized)
        }

        // Nothing was written — the queue is still empty, not silently corrupted.
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `the fast recovery scan still detects a corrupted body, not just a truncated one`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val idB = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())

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
    fun `read handle is cached and not closed or churned on multiple reads`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())

        val readHandleField = DiskQueue::class.java.getDeclaredField("readHandle")
        readHandleField.isAccessible = true

        assertNull(readHandleField.get(queue))

        queue.peek()
        val handle1 = readHandleField.get(queue)
        kotlin.test.assertNotNull(handle1)

        queue.peek()
        val handle2 = readHandleField.get(queue)
        assertEquals(handle1, handle2)
    }

    @Test
    fun `isolated corruption in the middle is skipped and subsequent valid records are recovered`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id1 = queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val id2 = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())
        val id3 = queue.enqueue("POST", "/third", FrozenHttpHeaders.EMPTY, "third-body".encodeToByteArray())

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

    @Test
    fun `peek skips over corrupted records and returns the next valid one instead of null`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id1 = queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val id2 = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())

        // Corrupt "/first"'s record bytes.
        val bytes = FileSystem.SYSTEM.read(queuePath) { readByteArray() }
        val marker = "first-body".encodeToByteArray()
        val bodyStart = bytes.indexOfSubarray(marker)
        bytes[bodyStart] = (bytes[bodyStart] + 1).toByte()
        FileSystem.SYSTEM.write(queuePath) { write(bytes) }

        // peek() should skip the corrupted "/first" and return "/second".
        val peeked = queue.peek()
        kotlin.test.assertNotNull(peeked)
        assertEquals("/second", peeked.meta.url)
        assertEquals(id2, peeked.id)
    }

    @Test
    fun `two DiskQueue instances on the same path serialize concurrent writes safely`() = runBlocking {
        // Dispatchers.Default runs on a real thread pool: DiskQueue's cross-process file lock
        // (PlatformQueueFileLock) must genuinely block a contending thread here, not just look
        // safe because everything happened to run on one thread. Plain runBlocking without an
        // explicit dispatcher is single-threaded, so 20 coroutines on it never actually overlap
        // at the OS level and this test would pass even if the lock implementation were broken —
        // see PlatformQueueFileLock's own doc for the JVM-specific failure mode that hid behind
        // that false confidence (OverlappingFileLockException instead of blocking).
        val path = queuePath
        val queueA = DiskQueue(path)
        val queueB = DiskQueue(path)

        withContext(Dispatchers.Default) {
            coroutineScope {
                List(20) { index ->
                    async {
                        val queue = if (index % 2 == 0) {
                            queueA
                        } else {
                            queueB
                        }
                        queue.enqueue(
                            method = "POST",
                            url = "https://example.com/$index",
                            headers = FrozenHttpHeaders.EMPTY,
                            body = "body-$index".encodeToByteArray(),
                        )
                    }
                }.awaitAll()
            }
        }

        val reopened = DiskQueue(path)
        assertEquals(20, reopened.size())
    }

    @Test
    fun `enqueue rejects a record whose packed on-disk length would overflow the index`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val hugeBody = ByteArray(DiskQueueConstants.MAX_RECORD_FIELD_SIZE)

        assertFailsWith<RecordTooLargeException> {
            queue.enqueue("POST", "https://example.com/huge", FrozenHttpHeaders.EMPTY, hugeBody)
        }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `close still releases the read handle when closing the append sink throws`() = runBlocking {
        val throwingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                val real = super.appendingSink(file, mustExist)
                return object : Sink by real {
                    override fun close() {
                        real.close()
                        throw IOException("simulated append-sink close failure")
                    }
                }
            }
        }
        val queue = DiskQueue(queuePath, throwingFs)
        queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        queue.peek() // populates the cached read handle so there is something to release

        assertFailsWith<IOException> { queue.close() }

        val appendSinkField = DiskQueue::class.java.getDeclaredField("appendSink").apply { isAccessible = true }
        val readHandleField = DiskQueue::class.java.getDeclaredField("readHandle").apply { isAccessible = true }
        assertNull(appendSinkField.get(queue), "appendSink should be cleared even though closing it threw")
        assertNull(readHandleField.get(queue), "readHandle should still be released after the append sink's close throws")
    }
}
