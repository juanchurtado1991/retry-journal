package com.retryjournal.queue

import com.retryjournal.freshTestDir
import com.retryjournal.peekAll
import com.retryjournal.queue.disk.DiskQueue
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness at scale — [DiskQueueTest] and [DiskQueueCompactionTest] cover the mechanics with a
 * handful of entries each; this exercises the same invariants over thousands of operations, where
 * bugs that only show up after many compaction cycles or a large live set have room to appear.
 * Not a benchmark — there's no timing assertion, only correctness ones.
 */
class DiskQueueLoadTest {

    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        queuePath = freshTestDir("retry-journal-load-test").resolve("queue.bin")
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(queuePath.parent!!, mustExist = false)
    }

    @Test
    fun `enqueueing and draining a large number of entries preserves FIFO order throughout`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val count = 5_000

        val ids = (0 until count).map { index ->
            queue.enqueue(
                "POST",
                "https://example.com/$index",
                FrozenHttpHeaders.EMPTY,
                "body-$index".encodeToByteArray(),
            )
        }
        assertEquals(count, queue.size())

        ids.forEachIndexed { index, expectedId ->
            val head = queue.peek()
            assertEquals(expectedId, head?.id, "FIFO order broke at position $index")
            assertEquals("https://example.com/$index", head?.meta?.url, "FIFO order broke at position $index")
            queue.remove(expectedId)
        }
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `sustained enqueue-drain churn across many compaction cycles keeps every invariant and survives a reopen`() =
        runBlocking {
            val queue = DiskQueue(queuePath)
            val totalSteps = 4_000
            val random = Random(20260716L)

            // FIFO order: (id, url) for everything currently live, mirroring how the real runtime
            // only ever drains from the head. COMPACTION_DEAD_RATIO_THRESHOLD is 0.8 — this churn
            // pattern (enqueue while draining from the front) is exactly what pushes dead bytes
            // over that threshold repeatedly, so this exercises many real compaction cycles, not
            // just one.
            val expectedLive = ArrayDeque<Pair<QueueEntryId, String>>()
            var nextIndex = 0
            var invariantChecks = 0

            repeat(totalSteps) { step ->
                val shouldEnqueue = expectedLive.size < 50 || random.nextInt(3) != 0
                if (shouldEnqueue) {
                    val url = "https://example.com/item-${nextIndex++}"
                    val id = queue.enqueue("POST", url, FrozenHttpHeaders.EMPTY, "body-$step".encodeToByteArray())
                    expectedLive.addLast(id to url)
                } else {
                    val (id, _) = expectedLive.removeFirst()
                    queue.remove(id)
                }

                if (step % 200 == 0) {
                    queue.assertInvariantsHold()
                    invariantChecks++
                }
            }
            queue.assertInvariantsHold()
            assertTrue(invariantChecks > 10, "sanity check that the periodic invariant check actually ran")

            assertEquals(expectedLive.size, queue.size())
            val actualLive = queue.peekAll()
            assertEquals(expectedLive.size, actualLive.size)
            expectedLive.forEachIndexed { index, (expectedId, expectedUrl) ->
                assertEquals(expectedId, actualLive[index].id, "live set diverged at position $index")
                assertEquals(expectedUrl, actualLive[index].meta.url, "live set diverged at position $index")
            }

            // Reopen from scratch — forces the crash-recovery scan to rebuild the index from the
            // (by now heavily compacted) file and must land on exactly the same live set.
            queue.close()
            val reopened = DiskQueue(queuePath)
            assertEquals(expectedLive.size, reopened.size())
            val reopenedLive = reopened.peekAll()
            expectedLive.forEachIndexed { index, (expectedId, expectedUrl) ->
                assertEquals(expectedId, reopenedLive[index].id, "reopen diverged at position $index")
                assertEquals(expectedUrl, reopenedLive[index].meta.url, "reopen diverged at position $index")
            }
        }
}
