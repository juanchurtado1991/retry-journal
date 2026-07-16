package com.retryjournal.queue.disk

/** One live entry: a sequence id and its packed on-disk length/offset (see [LiveEntryIndex]'s own doc). */
internal data class LiveEntry(val sequenceId: Long, val packed: Long)

/**
 * Dense, array-backed replacement for the `LinkedHashMap<Long, Long>` [DiskQueue] used to hold
 * `sequenceId -> packed length/offset` for every live entry — held in memory for the lifetime of
 * every open queue, one entry per still-queued request.
 *
 * Measured with JOL (see docs/development.md's Performance report): `LinkedHashMap<Long, Long>`
 * costs ~98 bytes/entry (an `Entry` object plus two boxed `Long`s, none of which a primitive index
 * actually needs). This costs 8 bytes/entry — one raw `long`, nothing else — by exploiting two
 * things that are always true for how this index is actually used:
 *
 * 1. Sequence ids are assigned monotonically in insertion order ([DiskQueue.nextSequenceId] only
 *    ever increments), so a live entry's position is always `sequenceId - baseSequenceId` — an
 *    array index, not something that needs hashing.
 * 2. A legitimate packed value is never exactly `0L`: [PackedIndexEntry.pack]'s length component
 *    alone is always at least 21 (`DiskQueueConstants.RECORD_HEADER_SIZE` +
 *    `SEQUENCE_FIELD_SIZE` + 2×`LENGTH_FIELD_SIZE`, the minimum framing overhead for the smallest
 *    possible live record), so `0L` is always safe as the "no entry here" sentinel — no separate
 *    presence bitmap needed.
 *
 * Removing entries leaves holes (`remove()` doesn't shift anything — that would be O(n) per
 * removal). [headSlot] tracks the lowest slot that might still be occupied, advanced past
 * consecutive holes as they're removed; when that dead leading space grows past half the array,
 * the live region is shifted back to slot 0 and [baseSequenceId] moves forward to match — bounding
 * wasted space to a constant factor of the *live* count, not the cumulative historical count,
 * without needing to shift on every single removal.
 *
 * `set()` only supports inserting at or after the current [baseSequenceId] — appending, never
 * inserting into the middle — because that's the only pattern any caller in this codebase actually
 * needs ([DiskQueue] only ever assigns brand-new, monotonically increasing sequence ids; recovery
 * and compaction rebuild this index by replaying the file in order via [replaceAllWith]).
 */
internal class LiveEntryIndex {
    private var packed = LongArray(INITIAL_CAPACITY)
    private var baseSequenceId = 0L
    private var baseEstablished = false

    /** Lowest slot that might be occupied, relative to [baseSequenceId]. */
    private var headSlot = 0

    /** One past the highest slot that might be occupied, relative to [baseSequenceId]. */
    private var tailSlot = 0

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    operator fun get(sequenceId: Long): Long? {
        val slot = slotOf(sequenceId) ?: return null
        val value = packed[slot]
        return if (value == EMPTY) null else value
    }

    fun containsKey(sequenceId: Long): Boolean = get(sequenceId) != null

    operator fun set(sequenceId: Long, value: Long) {
        require(value != EMPTY) { "packed value must never be exactly 0 — that's the empty-slot sentinel" }
        if (!baseEstablished) {
            baseSequenceId = sequenceId
            headSlot = 0
            tailSlot = 0
            baseEstablished = true
        }
        require(sequenceId >= baseSequenceId) {
            "LiveEntryIndex only supports appending at/after the current base " +
                "(sequenceId=$sequenceId, base=$baseSequenceId)"
        }

        var relative = (sequenceId - baseSequenceId).toInt()
        if (relative >= packed.size) {
            compactLeadingDeadSpace()
            relative = (sequenceId - baseSequenceId).toInt()
            if (relative >= packed.size) {
                grow(minCapacity = relative + 1)
            }
        }

        if (packed[relative] == EMPTY) {
            size++
        }
        packed[relative] = value
        if (relative >= tailSlot) {
            tailSlot = relative + 1
        }
    }

    fun remove(sequenceId: Long): Long? {
        val slot = slotOf(sequenceId) ?: return null
        val old = packed[slot]
        if (old == EMPTY) return null

        packed[slot] = EMPTY
        size--

        if (size == 0) {
            // Nothing live anywhere — collapse fully instead of leaving a big dead array around.
            // The next set() re-establishes baseSequenceId fresh, right where the next entry is.
            headSlot = 0
            tailSlot = 0
            baseEstablished = false
            return old
        }

        if (slot == headSlot) {
            while (headSlot < tailSlot && packed[headSlot] == EMPTY) {
                headSlot++
            }
            if (headSlot >= packed.size / 2) {
                compactLeadingDeadSpace()
            }
        }
        return old
    }

    fun clear() {
        packed.fill(EMPTY, 0, tailSlot)
        headSlot = 0
        tailSlot = 0
        size = 0
        baseEstablished = false
    }

    /** Discards everything currently held and adopts [source] wholesale — [source]'s iteration
     * order must be ascending by sequence id (true for the `LinkedHashMap`s recovery/compaction
     * build, since both replay the on-disk file front-to-back). Used when [DiskQueue] adopts a
     * fresh crash-recovery scan or a post-compaction index. */
    fun replaceAllWith(source: Map<Long, Long>) {
        clear()
        for ((sequenceId, value) in source) {
            set(sequenceId, value)
        }
    }

    fun firstSequenceIdOrNull(): Long? = if (size == 0) null else baseSequenceId + headSlot

    fun firstEntryOrNull(): LiveEntry? =
        if (size == 0) null else LiveEntry(baseSequenceId + headSlot, packed[headSlot])

    /** FIFO order (ascending sequence id) — the same iteration order `LinkedHashMap` gave every
     * caller here, since entries are always inserted in ascending order in the first place. */
    inline fun forEach(action: (sequenceId: Long, packed: Long) -> Unit) {
        for (slot in headSlot until tailSlot) {
            val value = packed[slot]
            if (value != EMPTY) {
                action(baseSequenceId + slot, value)
            }
        }
    }

    private fun slotOf(sequenceId: Long): Int? {
        if (!baseEstablished) return null
        val relative = sequenceId - baseSequenceId
        if (relative < 0 || relative >= packed.size) return null
        return relative.toInt()
    }

    /** Shifts the live region down to slot 0 and advances [baseSequenceId] to match, reclaiming
     * dead leading space without allocating — a no-op if there's no leading space to reclaim. */
    private fun compactLeadingDeadSpace() {
        if (headSlot == 0) return
        val liveSpan = tailSlot - headSlot
        packed.copyInto(packed, destinationOffset = 0, startIndex = headSlot, endIndex = tailSlot)
        packed.fill(EMPTY, liveSpan, tailSlot)
        baseSequenceId += headSlot
        tailSlot = liveSpan
        headSlot = 0
    }

    private fun grow(minCapacity: Int) {
        // LongArray.copyOf zero-fills new slots, and EMPTY == 0L, so the grown tail is already
        // correctly "empty" with no extra work.
        packed = packed.copyOf(maxOf(minCapacity, packed.size * 2))
    }

    companion object {
        // Not private: the internal inline forEach() below needs to reference EMPTY, and an
        // inline function's referenced symbols must be at least as visible as the function itself.
        private const val INITIAL_CAPACITY: Int = 8
        const val EMPTY: Long = 0L
    }
}
