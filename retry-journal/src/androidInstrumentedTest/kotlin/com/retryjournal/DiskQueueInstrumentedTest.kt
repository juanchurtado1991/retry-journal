package com.retryjournal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.disk.DiskQueue
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `testDebugUnitTest` (see [com.retryjournal.queue.DiskQueueTest] and friends) runs on a plain
 * JVM — `java.io`/`okio.FileSystem.SYSTEM` work there, but nothing exercises this against a real
 * Android app sandbox (a real `Context`, a real device/emulator filesystem, a real Dalvik/ART
 * runtime instead of the JVM's own `java.nio.file.Files`). This is the one place that does —
 * runs on `connectedDebugAndroidTest` against an emulator or device, not the JVM.
 */
@RunWith(AndroidJUnit4::class)
class DiskQueueInstrumentedTest {

    private lateinit var queueDir: okio.Path

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // context.filesDir is the app's real private storage on-device — not a JVM temp dir.
        queueDir = (context.filesDir.path + "/retry-journal-instrumented-test-${System.nanoTime()}").toPath()
    }

    @After
    fun tearDown() {
        okio.FileSystem.SYSTEM.deleteRecursively(queueDir, mustExist = false)
    }

    @Test
    fun enqueuePeekAndRemoveRoundTripOnARealAndroidDevice() = runBlocking {
        val queue = DiskQueue(queueDir.resolve("queue.bin"))

        assertTrue(queue.isEmpty())
        queue.enqueue(
            method = "POST",
            url = "https://example.com/a",
            headers = FrozenHttpHeaders.of("Content-Type" to "application/json"),
            body = "device-body".encodeToByteArray(),
        )
        assertEquals(1, queue.size())

        val entry = queue.peek()
        assertEquals("POST", entry?.meta?.method)
        assertEquals("device-body", entry?.body?.decodeToString())

        queue.remove(entry!!.id)
        assertTrue(queue.isEmpty())
        queue.close()
    }

    @Test
    fun aFreshDiskQueueInstanceRecoversStateWrittenByThePreviousOneOnDevice() = runBlocking {
        val queuePath = queueDir.resolve("queue.bin")
        val first = DiskQueue(queuePath)
        val idA = first.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        first.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        first.remove(idA)
        first.close()

        val reopened = DiskQueue(queuePath)
        assertEquals(1, reopened.size())
        assertEquals("/b", reopened.peek()?.meta?.url)
        reopened.close()
    }

    @Test
    fun enqueueingManyEntriesOnDeviceStorageStaysCorrectAndFifoOrdered() = runBlocking {
        val queue = DiskQueue(queueDir.resolve("queue.bin"))
        val count = 500 // instrumented runs are much slower than JVM/Simulator — keep this modest

        val ids = (0 until count).map { index ->
            queue.enqueue("POST", "https://example.com/$index", FrozenHttpHeaders.EMPTY, "body-$index".encodeToByteArray())
        }
        assertEquals(count, queue.size())

        ids.forEachIndexed { index, expectedId ->
            val head = queue.peek()
            assertEquals(expectedId, head?.id, "FIFO order broke at position $index")
            queue.remove(expectedId)
        }
        assertTrue(queue.isEmpty())
        queue.close()
    }
}
