package com.retryjournal.queue

import com.retryjournal.freshTestDir
import com.retryjournal.TestCounter
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.platform.currentTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayClaimTest {

    private lateinit var dir: Path
    private lateinit var queuePath: Path

    @BeforeTest
    fun setUp() {
        dir = freshTestDir("retry-journal-replay-claim-test")
        queuePath = (dir.toString() + "/queue.bin").toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
    }

    @Test
    fun `a second prepareHeadForReplay stops when the head is already claimed`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        queueA.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val first = queueA.prepareHeadForReplay()
        assertTrue(first is HeadReplayPrepareResult.Ready)

        val queueB = DiskQueue(queuePath)
        val second = queueB.prepareHeadForReplay()
        assertEquals(HeadReplayPrepareResult.HeadBlocked, second)
    }

    @Test
    fun `completeHeadReplay clears the claim so the next entry can be prepared`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val first = queue.prepareHeadForReplay()
        assertTrue(first is HeadReplayPrepareResult.Ready)
        queue.completeHeadReplay(id)

        assertTrue(queue.isEmpty())
        val again = queue.prepareHeadForReplay()
        assertEquals(HeadReplayPrepareResult.Empty, again)
    }

    @Test
    fun `completeHeadReplay fails without prior head preparation`() {
        runBlocking {
            val queue = DiskQueue(queuePath)
            val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

            assertFailsWith<IllegalStateException> {
                queue.completeHeadReplay(id)
            }
        }
    }

    @Test
    fun `renewHeadReplayClaim refreshes a stale claim timestamp`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        assertTrue(queue.prepareHeadForReplay() is HeadReplayPrepareResult.Ready)

        val claimPath = ReplayClaim.claimPath(queuePath)
        val staleAt = currentTimeMillis() - DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS - 1L
        ReplayClaim.write(FileSystem.SYSTEM, claimPath, id.sequenceId, staleAt)
        assertTrue(ReplayClaim.isStale(ReplayClaim.read(FileSystem.SYSTEM, claimPath)!!, currentTimeMillis()))

        queue.renewHeadReplayClaim(id)

        val refreshed = ReplayClaim.read(FileSystem.SYSTEM, claimPath)!!
        assertTrue(!ReplayClaim.isStale(refreshed, currentTimeMillis()))
    }

    @Test
    fun `a stale replay claim is ignored instead of blocking forever`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val claimPath = ReplayClaim.claimPath(queuePath)
        val staleAt = currentTimeMillis() - DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS - 1L
        ReplayClaim.write(FileSystem.SYSTEM, claimPath, id.sequenceId, staleAt)

        val prepared = queue.prepareHeadForReplay()
        assertTrue(prepared is HeadReplayPrepareResult.Ready)
    }

    @Test
    fun `parallel prepareHeadForReplay from two queue instances delivers exactly one claim`() = runBlocking {
        val queueA = DiskQueue(queuePath)
        queueA.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val queueB = DiskQueue(queuePath)
        val wins = TestCounter(0)
        val blocked = TestCounter(0)

        coroutineScope {
            val first = async { queueA.prepareHeadForReplay() }
            val second = async { queueB.prepareHeadForReplay() }
            listOf(first.await(), second.await()).forEach { result ->
                when (result) {
                    is HeadReplayPrepareResult.Ready -> wins.incrementAndGet()
                    is HeadReplayPrepareResult.HeadBlocked -> blocked.incrementAndGet()
                    is HeadReplayPrepareResult.Empty -> Unit
                }
            }
        }

        assertEquals(1, wins.get())
        assertEquals(1, blocked.get())
    }

    @Test
    fun `a claim timestamp modestly in the future from a backward clock jump is not treated as stale`() = runBlocking {
        // Regression: isStale() used to treat *any* timestamp more than 60s in the future as
        // stale, on the theory it could only mean a corrupt clock/file. But a genuine backward
        // wall-clock jump (NTP correction, a device resuming from suspension) routinely exceeds
        // 60s while the claim underneath it is still perfectly active — releasing it risked a
        // second process replaying the same head entry and duplicating a non-idempotent POST.
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val claimPath = ReplayClaim.claimPath(queuePath)
        val futureAt = currentTimeMillis() + 5 * 60_000L // 5 min — a realistic clock correction
        ReplayClaim.write(FileSystem.SYSTEM, claimPath, id.sequenceId, futureAt)

        val prepared = queue.prepareHeadForReplay()
        assertEquals(HeadReplayPrepareResult.HeadBlocked, prepared)
    }

    @Test
    fun `a claim timestamp far in the future is treated as stale instead of blocking forever`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())

        val claimPath = ReplayClaim.claimPath(queuePath)
        val futureAt = currentTimeMillis() + 86_400_000L // +1 day — well beyond clock-skew slack
        ReplayClaim.write(FileSystem.SYSTEM, claimPath, id.sequenceId, futureAt)

        val prepared = queue.prepareHeadForReplay()
        assertTrue(prepared is HeadReplayPrepareResult.Ready)
    }

    @Test
    fun `prepareHeadForReplay clears a non-head non-stale claim instead of blocking the queue`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        ReplayClaim.write(
            FileSystem.SYSTEM,
            ReplayClaim.claimPath(queuePath),
            idB.sequenceId,
            currentTimeMillis(),
        )

        val other = DiskQueue(queuePath)
        val prepared = other.prepareHeadForReplay()
        assertTrue(prepared is HeadReplayPrepareResult.Ready)
        assertEquals("/a", prepared.entry.meta.url)
    }

    @Test
    fun `prepareHeadForReplay clears an orphan claim for a removed sequence id`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        assertTrue(queue.prepareHeadForReplay() is HeadReplayPrepareResult.Ready)
        queue.completeHeadReplay(id)

        ReplayClaim.write(
            FileSystem.SYSTEM,
            ReplayClaim.claimPath(queuePath),
            id.sequenceId,
            currentTimeMillis(),
        )

        queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())

        val other = DiskQueue(queuePath)
        val prepared = other.prepareHeadForReplay()
        assertTrue(prepared is HeadReplayPrepareResult.Ready)
        assertEquals("/b", prepared.entry.meta.url)
    }

    @Test
    fun `regression prepareHeadForReplay is not HeadBlocked when only a non-head claim is active`() = runBlocking {
        val queue = DiskQueue(queuePath)
        queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        ReplayClaim.write(
            FileSystem.SYSTEM,
            ReplayClaim.claimPath(queuePath),
            idB.sequenceId,
            currentTimeMillis(),
        )

        val prepared = queue.prepareHeadForReplay()

        assertTrue(prepared is HeadReplayPrepareResult.Ready)
        assertEquals("/a", prepared.entry.meta.url)
    }
}
