package com.retryjournal.queue.disk

import com.retryjournal.queue.DeliveryJournal
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import com.retryjournal.queue.HeadReplayPrepareResult
import com.retryjournal.queue.LifecycleGate
import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.RecordFileHandles
import com.retryjournal.queue.QueueInvariants
import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.platform.PlatformQueueFileLock
import com.retryjournal.queue.platform.currentTimeMillis
import com.retryjournal.queue.platform.systemFileSystem
import com.retryjournal.queue.record.PackedIndexEntry
import com.retryjournal.queue.platform.ioDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Append-only, crash-safe FIFO queue backed by a single file. Nothing is ever rewritten in
 * place: `enqueue()` appends a live record, `remove()` appends a tombstone referencing it
 * (never rewrites it), and compaction only ever replaces the whole file atomically. This is the
 * append-only + atomic-rename pattern proven by kmpworkmanager's own queue, deliberately avoiding
 * square/tape's in-place 16-byte header rewrite (its root corruption cause on abrupt shutdown).
 * The crash-recovery scan lives in [DiskQueueRecovery], reclaiming dead space lives in
 * [DiskQueueCompactor] — both stateless, operating only on the file and whatever state this class
 * hands them, so they're safe to reason about independently of this class's own bookkeeping.
 *
 * Locked mutation helpers are split across [DiskQueueIndexSync], [DiskQueueEnqueueOps],
 * [DiskQueueHeadOps], [DiskQueuePeekOps], [DiskQueueScrubOps], [DiskQueueRemovalOps],
 * [DiskQueueReadOps], and [DiskQueueCompactionOps].
 *
 * All public operations are serialized by a single [Mutex] within one process, and by an advisory
 * file lock (`<queuePath>.lock`) across processes sharing the same queue file — a foreground app
 * and a background worker in a separate process can safely open the same path.
 *
 * [peek] skips corrupt head entries (tombstoning them) so [com.retryjournal.engine.RetryJournalEngine]
 * never stalls on unreadable data; [size] and [peekIds] only count entries that can actually be read.
 *
 * **Threading contract:**
 * - Safe to call from any dispatcher — this class always does its own blocking file I/O off the
 *   caller's thread, not on whatever dispatcher happened to call it.
 * - [close] throws [IllegalStateException] if another operation on this instance is still
 *   running. Same rule as closing any other resource: make sure nothing is still using it first.
 *   (Mechanism details for both, if you're working on this class rather than just calling it, are
 *   on [withQueueLock] and [close] themselves.)
 */
class DiskQueue(
    internal val path: Path,
    internal val fileSystem: FileSystem,
    internal val maxRecordFieldSize: Int = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
) {
    constructor(
        path: Path,
        maxRecordFieldSize: Int = DiskQueueConstants.MAX_RECORD_FIELD_SIZE,
    ) : this(path, systemFileSystem(), maxRecordFieldSize)

    init {
        require(maxRecordFieldSize > 0) {
            DiskQueueConstants.INVALID_MAX_RECORD_FIELD_SIZE_MESSAGE
        }
        require(
            DiskQueueConstants.RECORD_FIXED_OVERHEAD + 2L * maxRecordFieldSize <=
                DiskQueueConstants.MAX_PACKABLE_RECORD_LENGTH
        ) {
            DiskQueueConstants.MAX_RECORD_FIELD_SIZE_UNPACKABLE_MESSAGE
        }
    }

    private val mutex = Mutex()
    private val processLock = PlatformQueueFileLock(
        lockPath = (path.toString() + DiskQueueConstants.LOCK_FILE_SUFFIX).toPath(),
        fileSystem,
    )

    /** Sequence id -> packed length and offset — see [PackedIndexEntry] for the bit layout — in
     * FIFO order. */
    internal val liveOffsetsBySequence = LinkedHashMap<Long, Long>()
    internal var fileLength = 0L
    internal var deadBytes = 0L
    internal var nextSequenceId = 0L
    internal var opened = false
    internal var lastKnownGeneration = 0L

    internal var scrubScratch = LongArray(8)
    internal var scrubScratchCount = 0

    internal val fileHandles = RecordFileHandles(fileSystem, path)

    private val lifecycleGate = LifecycleGate(
        closedMessage = DiskQueueConstants.QUEUE_CLOSED_MESSAGE,
        closeWhileBusyMessage = DiskQueueConstants.CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE,
    )

    /** [ioDispatcher] instead of trusting the caller's own dispatcher: every operation here does
     * blocking file I/O (Okio's [FileSystem] is synchronous, and so is [PlatformQueueFileLock]),
     * and running that on, say, [kotlinx.coroutines.Dispatchers.Default]'s CPU-sized pool risks
     * starving other CPU-bound work sharing it under real contention. */
    internal suspend inline fun <T> withQueueLock(
        crossinline block: () -> T
    ): T = withContext(ioDispatcher) {
        mutex.withLock {
            lifecycleGate.enter()
            try {
                processLock.acquire()
                try {
                    refreshIndexIfNeededLocked()
                    block()
                } finally {
                    processLock.release()
                }
            } finally {
                lifecycleGate.leave()
            }
        }
    }

    suspend fun enqueue(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): QueueEntryId = withQueueLock {
        ensureOpenLocked()

        val sequenceId = nextSequenceId
        val metaBytes = encodeEnqueueMeta(method, url, headers)
        validateEnqueueFieldSizes(metaBytes, body)
        val packedLength = computePackedLiveRecordLength(metaBytes, body)
        appendLiveRecordLocked(sequenceId, metaBytes, body, packedLength)

        QueueEntryId(sequenceId)
    }

    /** Prepares the oldest readable entry for replay: writes a cross-process
     * [com.retryjournal.queue.ReplayClaim] so a second flusher in another process cannot send the same head entry
     * concurrently. Call [completeHeadReplay] after a successful delivery/dead-letter, or
     * [abortHeadReplayClaim] when replay stops early without removing the entry. */
    internal suspend fun prepareHeadForReplay(): HeadReplayPrepareResult = withQueueLock {
        ensureOpenLocked()
        val claimPath = ReplayClaim.claimPath(path)
        ReplayClaim.clearIfStale(fileSystem, claimPath)

        val scan = scanFirstReadableHeadLocked()
        finalizeHeadScrubIfNeededLocked(scan.removedAny)
        DeliveryJournal.clearStaleJournalsLocked(this, scan.entry?.id?.sequenceId)

        val entry = scan.entry
        if (entry == null) {
            if (isHeadBlockedByActiveClaimLocked()) {
                return@withQueueLock HeadReplayPrepareResult.HeadBlocked
            }
            return@withQueueLock HeadReplayPrepareResult.Empty
        }
        claimHeadForReplay(entry, claimPath)
    }

    /** Removes a replayed entry and clears its [ReplayClaim] — call after 2xx delivery or
     * dead-letter persistence. Always clears the claim in `finally`, even when removal fails. */
    internal suspend fun completeHeadReplay(entryId: QueueEntryId) = withQueueLock {
        ensureOpenLocked()
        val claimPath = ReplayClaim.claimPath(path)
        try {
            validateCompleteHeadReplayLocked(entryId, claimPath)
            if (!liveOffsetsBySequence.containsKey(entryId.sequenceId)) {
                error(DiskQueueConstants.COMPLETE_HEAD_SEQUENCE_MISSING_MESSAGE)
            }
            removeLocked(entryId.sequenceId)
            compactIfNeededLocked()
            bumpDiskGenerationLocked()
        } finally {
            ReplayClaim.delete(fileSystem, claimPath)
        }
    }

    /** Refreshes the cross-process [ReplayClaim] timestamp while a replay HTTP round-trip is in
     * flight — [com.retryjournal.engine.RetryJournalEngine] calls this periodically so slow
     * uploads do not outlive [DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS]. */
    internal suspend fun renewHeadReplayClaim(entryId: QueueEntryId) = withQueueLock {
        ensureOpenLocked()
        val claimPath = ReplayClaim.claimPath(path)
        val activeClaim = ReplayClaim.read(fileSystem, claimPath) ?: return@withQueueLock
        if (activeClaim.sequenceId != entryId.sequenceId) {
            return@withQueueLock
        }
        ReplayClaim.write(
            fileSystem,
            claimPath,
            entryId.sequenceId,
            currentTimeMillis(),
        )
    }

    /** Clears the [ReplayClaim] without removing the entry — call when replay stops early. */
    internal suspend fun abortHeadReplayClaim() = withQueueLock {
        ensureOpenLocked()
        ReplayClaim.delete(fileSystem, ReplayClaim.claimPath(path))
    }

    /** The oldest readable live entry, or `null` if the queue is empty. Corrupt head entries are
     * tombstoned so the queue cannot stall behind unreadable data. */
    suspend fun peek(): QueueEntry? = withQueueLock {
        ensureOpenLocked()
        val scan = scanFirstReadableHeadLocked()
        finalizeHeadScrubIfNeededLocked(scan.removedAny)
        scan.entry
    }

    /** Every readable live entry, oldest first. Used for inspection UIs — not a hot path. */
    suspend fun peekAll(
        outResult: MutableCollection<QueueEntry>
    ): Int = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        appendAllReadableEntriesLocked(outResult)
    }

    /** A specific entry by id, or `null` if it was never enqueued or has already been removed.
     * Unreadable index slots (CRC/sequence mismatch) are tombstoned like [size] scrub does. */
    suspend fun get(id: QueueEntryId): QueueEntry? = withQueueLock {
        ensureOpenLocked()
        val packed = liveOffsetsBySequence[id.sequenceId] ?: return@withQueueLock null
        val entry = readLiveEntryAtLocked(id.sequenceId, PackedIndexEntry.unpackOffset(packed))
        if (entry != null) {
            return@withQueueLock entry
        }
        val claimPath = ReplayClaim.claimPath(path)
        if (ReplayClaim.isActiveClaimForSequence(fileSystem, claimPath, id.sequenceId)) {
            return@withQueueLock null
        }
        removeLocked(id.sequenceId)
        compactIfNeededLocked()
        bumpDiskGenerationLocked()
        null
    }

    /** Idempotent: removing an already-removed or unknown id is a no-op. */
    suspend fun remove(id: QueueEntryId) = withQueueLock {
        ensureOpenLocked()
        assertNotClaimedForRemoval(id.sequenceId)
        removeLocked(id.sequenceId)
        compactIfNeededLocked()
        bumpDiskGenerationLocked()
    }

    suspend fun isEmpty(): Boolean = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        liveOffsetsBySequence.isEmpty()
    }

    /** Count of readable live entries — corrupt index slots are scrubbed first. */
    suspend fun size(): Int = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        countReadableEntriesLocked()
    }

    /** The first [limit] readable live entry ids, oldest first. */
    suspend fun peekIds(limit: Int, outResult: MutableCollection<QueueEntryId>): Int =
        withQueueLock {
            ensureOpenLocked()
            scrubUnreadableEntriesLocked()
            collectReadableEntryIdsLocked(limit, outResult)
        }

    internal suspend fun peekAllRaw(action: (Long, FrozenHttpRequestMeta, ByteArray) -> Unit) =
        withQueueLock {
            ensureOpenLocked()
            scrubUnreadableEntriesLocked()
            forEachReadableEntryLocked { sequenceId, entry ->
                action(sequenceId, entry.meta, entry.body)
            }
        }

    internal fun isHeadBlockedByActiveClaimLocked(): Boolean =
        DiskQueueHeadOps.isHeadBlockedByActiveClaimLocked(this)

    internal suspend fun assertInvariantsHold() = withQueueLock {
        QueueInvariants.assertHoldLocked(this)
    }

    /** Closes this queue. [LifecycleGate] serializes [close] against new [withQueueLock]
     * operations so there is no TOCTOU window between an in-flight check and marking the
     * instance closed.
     *
     * [RecordFileHandles.closeAll] runs after the gate closes: if it throws, this instance stays
     * permanently closed rather than retryable — see [RecordFileHandles.closeAll]'s own doc. */
    internal fun closeForShutdown() {
        lifecycleGate.close()
    }

    fun close() {
        closeForShutdown()
        fileHandles.closeAll()
    }
}
