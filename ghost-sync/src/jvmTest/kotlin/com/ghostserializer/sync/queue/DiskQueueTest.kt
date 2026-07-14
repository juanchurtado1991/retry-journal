package com.ghostserializer.sync.queue

import com.ghostserializer.sync.indexOfSubarray
import com.ghostserializer.sync.peekAll
import com.ghostserializer.sync.peekIds
import com.ghostserializer.sync.queue.record.RecordTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Core CRUD, concurrency, and lifecycle behavior. See [DiskQueueRecoveryTest] for the
 * crash-recovery scan and [DiskQueueCompactionTest] for reclaiming dead space. */
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
    fun `read handle is cached and not closed or churned on multiple reads`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())

        assertNull(queue.readFileHandlesField("readHandle"))

        queue.peek()
        val handle1 = queue.readFileHandlesField("readHandle")
        kotlin.test.assertNotNull(handle1)

        queue.peek()
        val handle2 = queue.readFileHandlesField("readHandle")
        assertEquals(handle1, handle2)
    }

    @Test
    fun `peek skips over corrupted records and returns the next valid one instead of null`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
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

        assertNull(queue.readFileHandlesField("appendSink"), "appendSink should be cleared even though closing it threw")
        assertNull(queue.readFileHandlesField("readHandle"), "readHandle should still be released after the append sink's close throws")
    }

    @Test
    fun `close throws instead of racing an operation that is still in flight`() = runBlocking {
        // Holds a real enqueue() paused mid-write on a background thread, so close() is called
        // while activeOperationCount is genuinely nonzero — not a simulated value.
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val blockingFs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                operationStarted.countDown()
                releaseOperation.await()
                return super.appendingSink(file, mustExist)
            }
        }
        val queue = DiskQueue(queuePath, blockingFs)

        val enqueueJob = launch(Dispatchers.Default) {
            queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        }

        operationStarted.await()
        try {
            assertFailsWith<IllegalStateException> { queue.close() }
        } finally {
            releaseOperation.countDown()
            enqueueJob.join()
        }

        // close() correctly refused to tear the queue down mid-operation — it's still usable.
        assertEquals(1, queue.size())
        queue.close()
    }

    /** [DiskQueue] delegates its cached append sink/read handle to [RecordFileHandles] — reaches
     * through that private field to read one of [RecordFileHandles]'s own private fields by name. */
    private fun DiskQueue.readFileHandlesField(name: String): Any? {
        val fileHandlesField = DiskQueue::class.java.getDeclaredField("fileHandles").apply { isAccessible = true }
        val fileHandles = fileHandlesField.get(this)
        val field = RecordFileHandles::class.java.getDeclaredField(name).apply { isAccessible = true }
        return field.get(fileHandles)
    }
}
