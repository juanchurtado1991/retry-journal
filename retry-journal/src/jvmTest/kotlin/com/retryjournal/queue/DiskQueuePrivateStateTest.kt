package com.retryjournal.queue

import com.retryjournal.freshTestDir
import com.retryjournal.queue.disk.DiskQueue
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [DiskQueueTest] covers everything reachable through [DiskQueue]'s public API on every
 * platform. This file is JVM-only: it reaches into private fields via reflection to simulate
 * index corruption and to assert on cached-handle bookkeeping directly — there is no
 * multiplatform-safe way to do that, so it can't move to `commonTest`. */
class DiskQueuePrivateStateTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        queuePath = freshTestDir("retry-journal-private-state-test").resolve("queue.bin")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `read handle is cached and not closed or churned on multiple reads`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())

        assertNull(queue.readFileHandlesField("readHandle"))

        queue.peek()
        val handle1 = queue.readFileHandlesField("readHandle")
        assertNotNull(handle1)

        queue.peek()
        val handle2 = queue.readFileHandlesField("readHandle")
        assertEquals(handle1, handle2)
    }

    @Test
    fun `close still releases the read handle when closing the append sink throws`() = runBlocking {
        val throwingFs = object : okio.ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun appendingSink(file: Path, mustExist: Boolean): okio.Sink {
                val real = super.appendingSink(file, mustExist)
                return object : okio.Sink by real {
                    override fun close() {
                        real.close()
                        throw java.io.IOException("simulated append-sink close failure")
                    }
                }
            }
        }
        val queue = DiskQueue(queuePath, throwingFs)
        queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        queue.peek() // populates the cached read handle so there is something to release

        kotlin.test.assertFailsWith<java.io.IOException> { queue.close() }

        assertNull(queue.readFileHandlesField("appendSink"), "appendSink should be cleared even though closing it threw")
        assertNull(queue.readFileHandlesField("readHandle"), "readHandle should still be released after the append sink's close throws")
    }

    @Test
    fun `get() refuses to return an entry when the on-disk sequenceId doesn't match what the index expected`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val idA = queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first-body".encodeToByteArray())
        val idB = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second-body".encodeToByteArray())

        // Simulate the index pointing at the wrong offset for each id — a bug elsewhere in
        // index bookkeeping, or a tampered file, could produce exactly this. Without validating
        // the sequenceId actually read back against the one the index expected, get(idA) would
        // silently return "/second"'s meta/body mislabeled under idA's id.
        queue.swapIndexOffsets(idA.sequenceId, idB.sequenceId)

        assertNull(queue.get(idA))
        assertNull(queue.get(idB))
    }

    @Test
    fun `get tombstones an unreadable index slot instead of leaving a ghost entry`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id1 = queue.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())
        val id2 = queue.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second".encodeToByteArray())

        queue.repointIndexToWrongRecord(id1.sequenceId, id2.sequenceId)

        assertNull(queue.get(id1))
        assertEquals(1, queue.size())
        assertEquals("/second", queue.peek()?.meta?.url)
    }

    @Test
    fun `scrubbing unreadable entries bumps the generation counter for cross-process refresh`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        val id1 = queueA.enqueue("POST", "/first", FrozenHttpHeaders.EMPTY, "first".encodeToByteArray())
        val id2 = queueA.enqueue("POST", "/second", FrozenHttpHeaders.EMPTY, "second".encodeToByteArray())

        val queueB = DiskQueue(queuePath)
        assertEquals(2, queueB.size())

        queueA.repointIndexToWrongRecord(id1.sequenceId, id2.sequenceId)

        assertEquals(2, queueB.size())

        assertEquals(1, queueA.size())

        assertEquals(1, queueB.size())
        assertEquals("/second", queueB.peek()?.meta?.url)
    }

    private fun DiskQueue.repointIndexToWrongRecord(wrongId: Long, siblingId: Long) {
        liveOffsetsBySequence[wrongId] = liveOffsetsBySequence[siblingId]!!
    }

    /** Reaches into [DiskQueue]'s internal live-offset index (visible directly here — no
     * reflection needed, `jvmTest` is an associated compilation of `commonMain`/`jvmMain`) and
     * swaps the packed offsets recorded for two sequence ids, so each id's index entry now
     * points at the *other* id's on-disk record — the exact mismatch
     * [com.retryjournal.queue.disk.readLiveEntryAtLocked]'s sequenceId check guards against. */
    private fun DiskQueue.swapIndexOffsets(sequenceIdA: Long, sequenceIdB: Long) {
        val packedA = liveOffsetsBySequence[sequenceIdA]!!
        val packedB = liveOffsetsBySequence[sequenceIdB]!!
        liveOffsetsBySequence[sequenceIdA] = packedB
        liveOffsetsBySequence[sequenceIdB] = packedA
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
