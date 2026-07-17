package com.retryjournal.queue.disk

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LiveEntryIndex] is what [DiskQueue] trusts to never lose track of a queued sequence id, so
 * this leans hard on differential fuzzing: run the exact same random operation sequence against
 * a [LiveEntryIndex] and a reference `LinkedHashMap<Long, Long>` side by side, and assert full
 * equivalence (contents, size, FIFO iteration order, head) after every single operation — not
 * just at the end. If they ever disagree, the fuzz test pinpoints the exact operation that broke
 * it, deterministically (fixed seed), rather than needing to guess.
 */
class LiveEntryIndexTest {

    @Test
    fun `a fresh index is empty`() {
        val index = LiveEntryIndex()
        assertTrue(index.isEmpty())
        assertEquals(0, index.size)
        assertNull(index.firstSequenceIdOrNull())
        assertNull(index.firstEntryOrNull())
        assertNull(index[0L])
        assertTrue(!index.containsKey(0L))
    }

    @Test
    fun `set then get round-trips a single entry`() {
        val index = LiveEntryIndex()
        index[5L] = 999L
        assertEquals(999L, index[5L])
        assertTrue(index.containsKey(5L))
        assertEquals(1, index.size)
        assertTrue(!index.isEmpty())
        assertEquals(5L, index.firstSequenceIdOrNull())
        assertEquals(LiveEntry(5L, 999L), index.firstEntryOrNull())
    }

    @Test
    fun `remove returns the removed value and the entry is gone afterward`() {
        val index = LiveEntryIndex()
        index[1L] = 100L
        assertEquals(100L, index.remove(1L))
        assertNull(index[1L])
        assertTrue(!index.containsKey(1L))
        assertTrue(index.isEmpty())
    }

    @Test
    fun `remove is idempotent for an unknown or already-removed sequence id`() {
        val index = LiveEntryIndex()
        index[1L] = 100L
        index.remove(1L)
        assertNull(index.remove(1L))
        assertNull(index.remove(999L))
    }

    @Test
    fun `entries iterate in FIFO ascending order regardless of removal pattern`() {
        val index = LiveEntryIndex()
        for (i in 0 until 10) {
            index[i.toLong()] = (i + 1).toLong() * 1000
        }
        index.remove(3L)
        index.remove(7L)

        val seen = mutableListOf<Long>()
        index.forEach { sequenceId, _ -> seen.add(sequenceId) }

        assertEquals(listOf(0L, 1L, 2L, 4L, 5L, 6L, 8L, 9L), seen)
        assertEquals(0L, index.firstSequenceIdOrNull())
    }

    @Test
    fun `head advances past holes left by removing the earliest entries first`() {
        val index = LiveEntryIndex()
        for (i in 0 until 5) {
            index[i.toLong()] = (i + 1).toLong()
        }
        assertEquals(0L, index.firstSequenceIdOrNull())
        index.remove(0L)
        assertEquals(1L, index.firstSequenceIdOrNull())
        index.remove(1L)
        assertEquals(2L, index.firstSequenceIdOrNull())
    }

    @Test
    fun `draining to empty and inserting again establishes a fresh base at the new sequence id`() {
        val index = LiveEntryIndex()
        for (i in 0 until 5) {
            index[i.toLong()] = (i + 1).toLong()
        }
        for (i in 0 until 5) {
            index.remove(i.toLong())
        }
        assertTrue(index.isEmpty())

        // A real DiskQueue's next sequence id only ever grows — simulate resuming far later.
        index[1_000L] = 42L
        assertEquals(1_000L, index.firstSequenceIdOrNull())
        assertEquals(42L, index[1_000L])
        assertEquals(1, index.size)
    }

    @Test
    fun `growth past initial capacity preserves every existing entry`() {
        val index = LiveEntryIndex()
        val count = 500 // several multiples past the small initial capacity
        for (i in 0 until count) {
            index[i.toLong()] = (i + 1).toLong()
        }
        assertEquals(count, index.size)
        for (i in 0 until count) {
            assertEquals((i + 1).toLong(), index[i.toLong()], "entry $i lost or corrupted across growth")
        }
    }

    @Test
    fun `set rejects a packed value of exactly zero`() {
        val index = LiveEntryIndex()
        assertFailsWith<IllegalArgumentException> { index[0L] = 0L }
    }

    @Test
    fun `set rejects a sequence id before the established base`() {
        val index = LiveEntryIndex()
        index[10L] = 1L
        index.remove(10L)
        index[20L] = 1L // re-establishes base at 20
        assertFailsWith<IllegalArgumentException> { index[15L] = 1L }
    }

    @Test
    fun `clear empties the index and lets a fresh base be established afterward`() {
        val index = LiveEntryIndex()
        for (i in 0 until 20) {
            index[i.toLong()] = (i + 1).toLong()
        }
        index.clear()
        assertTrue(index.isEmpty())
        assertEquals(0, index.size)

        index[500L] = 7L
        assertEquals(7L, index[500L])
        assertEquals(500L, index.firstSequenceIdOrNull())
    }

    @Test
    fun `replaceAllWith adopts every entry from a plain map in ascending order`() {
        val index = LiveEntryIndex()
        index[1L] = 1L // pre-existing content must be fully discarded

        val source = linkedMapOf(10L to 100L, 11L to 110L, 12L to 120L)
        index.replaceAllWith(source)

        assertEquals(3, index.size)
        assertEquals(10L, index.firstSequenceIdOrNull())
        assertEquals(100L, index[10L])
        assertEquals(110L, index[11L])
        assertEquals(120L, index[12L])
        assertNull(index[1L])
    }

    @Test
    fun `a fully-drained-then-refilled index does not silently retain a stale entry`() {
        val index = LiveEntryIndex()
        index[1L] = 1L
        index[2L] = 2L
        index.remove(1L)
        index.remove(2L)
        // Nothing live — a query for either old id must be null, not a leftover slot value.
        assertNull(index[1L])
        assertNull(index[2L])
    }

    @Test
    fun `repeated enqueue-drain churn keeps the array from growing without bound`() {
        // Not a hard assertion on the exact capacity (an implementation detail) — but sanity
        // checks that sustained head-first churn doesn't leave thousands of dead leading slots
        // by confirming correctness holds throughout many rebase cycles.
        val index = LiveEntryIndex()
        var nextId = 0L
        repeat(5_000) { step ->
            index[nextId] = nextId + 1
            nextId++
            if (step % 3 != 0) {
                // Drain roughly 2 out of every 3 steps, keeping a small live window — this is
                // exactly the access pattern that repeatedly exercises compactLeadingDeadSpace().
                index.firstSequenceIdOrNull()?.let { head -> index.remove(head) }
            }
        }
        // Whatever remains must still be internally consistent.
        var countedSize = 0
        var previous = -1L
        index.forEach { sequenceId, _ ->
            assertTrue(sequenceId > previous, "FIFO order violated after churn")
            previous = sequenceId
            countedSize++
        }
        assertEquals(index.size, countedSize)
    }

    /** Regression: the oldest-to-newest live sequence id gap used to size the backing array with
     * no upper bound — a permanently stuck head entry alongside heavy throughput behind it could
     * make `set()`/`replaceAllWith()` attempt a multi-gigabyte allocation (or, if the gap ever
     * exceeded [Int.MAX_VALUE], wrap into a corrupt negative array index). Both now fail loud with
     * a diagnosable [IllegalStateException] instead. */
    @Test
    fun `set rejects a span larger than MAX_SPAN instead of silently growing without bound`() {
        val index = LiveEntryIndex()
        index[0L] = 1L
        assertFailsWith<IllegalStateException> {
            index[LiveEntryIndex.MAX_SPAN + 1] = 1L
        }
    }

    @Test
    fun `replaceAllWith rejects a span larger than MAX_SPAN instead of allocating an unbounded array`() {
        val index = LiveEntryIndex()
        assertFailsWith<IllegalStateException> {
            index.replaceAllWith(linkedMapOf(0L to 1L, (LiveEntryIndex.MAX_SPAN + 1) to 1L))
        }
    }

    @Test
    fun `a span just under MAX_SPAN is still accepted`() {
        val index = LiveEntryIndex()
        index[0L] = 1L
        index[LiveEntryIndex.MAX_SPAN - 1] = 2L
        assertEquals(2, index.size)
    }

    @Test
    fun `differential fuzz against a reference LinkedHashMap agrees after every operation`() {
        val random = Random(20260717L)
        val index = LiveEntryIndex()
        val reference = LinkedHashMap<Long, Long>()
        var nextSequenceId = 0L

        repeat(20_000) { step ->
            // Bias toward insertion when small, toward removal when large — keeps a realistic
            // amount of churn instead of growing unboundedly or draining dry immediately.
            val shouldInsert = reference.size < 30 || random.nextInt(3) != 0
            if (shouldInsert) {
                val sequenceId = nextSequenceId++
                val value = sequenceId + 1 // always non-zero, matches the real "never exactly 0" invariant
                index[sequenceId] = value
                reference[sequenceId] = value
            } else {
                // Remove a uniformly random *currently live* key — not always the head — so
                // holes form in realistic, non-trivial patterns (scrub can remove any entry).
                val victim = reference.keys.elementAt(random.nextInt(reference.size))
                assertEquals(reference.remove(victim), index.remove(victim), "removed value mismatch at step $step")
            }

            assertEquals(reference.size, index.size, "size mismatch at step $step")
            assertEquals(reference.isEmpty(), index.isEmpty(), "isEmpty mismatch at step $step")
            assertEquals(reference.keys.firstOrNull(), index.firstSequenceIdOrNull(), "head mismatch at step $step")

            val indexSnapshot = LinkedHashMap<Long, Long>()
            index.forEach { sequenceId, packed -> indexSnapshot[sequenceId] = packed }
            assertEquals(reference, indexSnapshot, "full contents/order mismatch at step $step")
        }
    }
}
