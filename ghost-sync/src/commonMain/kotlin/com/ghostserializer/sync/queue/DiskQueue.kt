package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants.CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_PACKABLE_RECORD_LENGTH
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
import com.ghostserializer.sync.queue.platform.PlatformQueueFileLock
import com.ghostserializer.sync.queue.platform.currentTimeMillis
import com.ghostserializer.sync.queue.platform.ioDispatcher
import com.ghostserializer.sync.queue.record.PackedIndexEntry
import com.ghostserializer.sync.queue.record.RecordCodec
import com.ghostserializer.sync.queue.record.RecordReadResult
import com.ghostserializer.sync.queue.record.RecordTooLargeException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import kotlin.concurrent.Volatile

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
 * All public operations are serialized by a single [Mutex] within one process, and by an advisory
 * file lock (`<queuePath>.lock`) across processes sharing the same queue file — a foreground app
 * and a background worker in a separate process can safely open the same path.
 *
 * [peek] skips corrupt head entries (tombstoning them) so [com.ghostserializer.sync.engine.GhostSyncEngine]
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
    internal val fileSystem: FileSystem = FileSystem.SYSTEM,
    /** Caps a single meta or body field on disk — the default (64 MiB) is a safety net against
     * treating a corrupted length field as real (see [DiskQueueConstants.MAX_RECORD_FIELD_SIZE]),
     * not a target to size up to. Lowering it doesn't reduce memory use for anything already under
     * the limit — `enqueue()` only ever allocates exactly what you pass it — but it does reject
     * oversized bodies earlier and shrinks the worst-case allocation a corrupted length field could
     * trigger. Must stay the same for the lifetime of a given queue file: records are validated
     * against whatever value is passed in at the time they're read, not the value they were
     * written under. */
    private val maxRecordFieldSize: Int = MAX_RECORD_FIELD_SIZE,
) {
    init {
        require(maxRecordFieldSize > 0) {
            DiskQueueConstants.INVALID_MAX_RECORD_FIELD_SIZE_MESSAGE
        }
    }

    private val mutex = Mutex()
    private val processLock = PlatformQueueFileLock(
        lockPath = (path.toString() + DiskQueueConstants.LOCK_FILE_SUFFIX).toPath(),
        fileSystem,
    )

    /** Sequence id -> packed length and offset — see [PackedIndexEntry] for the bit layout — in
     * FIFO order. */
    private val liveOffsetsBySequence = LinkedHashMap<Long, Long>()
    private var fileLength = 0L
    private var deadBytes = 0L
    private var nextSequenceId = 0L
    private var opened = false
    private var closed = false
    private var lastKnownDiskModifiedAtMillis: Long? = null

    private var scrubScratch = LongArray(8)
    private var scrubScratchCount = 0

    private val fileHandles = RecordFileHandles(fileSystem, path)

    /** How many callers are currently inside [withQueueLock]'s critical section. Only ever
     * mutated while holding [mutex] (see [withQueueLock]), so concurrent increments/decrements
     * never race each other and lose an update; [Volatile] is what makes [close]'s own
     * *unsynchronized* read of it (it doesn't take [mutex] — see [close]) see the latest value
     * instead of a stale one cached on whatever thread called [close]. */
    @Volatile
    private var activeOperationCount = 0

    /** [ioDispatcher] instead of trusting the caller's own dispatcher: every operation here does
     * blocking file I/O (Okio's [FileSystem] is synchronous, and so is [PlatformQueueFileLock]),
     * and running that on, say, [kotlinx.coroutines.Dispatchers.Default]'s CPU-sized pool risks
     * starving other CPU-bound work sharing it under real contention. */
    private suspend inline fun <T> withQueueLock(
        crossinline block: () -> T
    ): T = withContext(ioDispatcher) {
        mutex.withLock {
            activeOperationCount++
            try {
                processLock.acquire()
                try {
                    refreshIndexIfNeededLocked()
                    ensureNotClosedLocked()
                    block()
                } finally {
                    processLock.release()
                }
            } finally {
                activeOperationCount--
            }
        }
    }

    private fun ensureNotClosedLocked() {
        if (closed) error(DiskQueueConstants.QUEUE_CLOSED_MESSAGE)
    }

    /** Another process may have appended while this instance held stale in-memory indexes — rescan
     * when the on-disk file size or last-modified time no longer matches what we last saw. */
    private fun refreshIndexIfNeededLocked() {
        if (!opened) return

        if (!fileSystem.exists(path)) {
            if (fileLength != 0L || lastKnownDiskModifiedAtMillis != null) {
                rescanFromDiskLocked()
            }
            return
        }
        val metadata = fileSystem.metadata(path)
        val diskSize = metadata.size ?: 0L
        val diskModifiedAtMillis = metadata.lastModifiedAtMillis
        if (diskSize == fileLength && diskModifiedAtMillis == lastKnownDiskModifiedAtMillis) {
            return
        }
        rescanFromDiskLocked()
    }

    private fun rescanFromDiskLocked() {
        liveOffsetsBySequence.clear()
        deadBytes = 0L
        nextSequenceId = 0L
        fileLength = 0L
        opened = false
        fileHandles.closeAppendSink()
        fileHandles.closeReadHandle()
        ensureOpenLocked()
    }

    private fun captureDiskMetadataLocked() {
        if (!fileSystem.exists(path)) {
            lastKnownDiskModifiedAtMillis = null
            return
        }
        lastKnownDiskModifiedAtMillis =
            fileSystem.metadata(path).lastModifiedAtMillis
    }

    suspend fun enqueue(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): QueueEntryId = withQueueLock {
        ensureOpenLocked()

        val sequenceId = nextSequenceId
        val meta = FrozenHttpRequestMeta(
            method = method,
            url = url,
            headers = headers,
            enqueuedAtMillis = currentTimeMillis(),
        )

        val metaBytes = Ghost.encodeToBytes(meta)
        if (metaBytes.size > maxRecordFieldSize) {
            throw RecordTooLargeException(
                DiskQueueConstants.META_FIELD_NAME,
                metaBytes.size,
                maxRecordFieldSize
            )
        }
        if (body.size > maxRecordFieldSize) {
            throw RecordTooLargeException(
                DiskQueueConstants.BODY_FIELD_NAME,
                body.size,
                maxRecordFieldSize
            )
        }

        val packedLength = DiskQueueConstants.RECORD_HEADER_SIZE +
                DiskQueueConstants.SEQUENCE_FIELD_SIZE +
                DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
                DiskQueueConstants.LENGTH_FIELD_SIZE + body.size
        if (packedLength > MAX_PACKABLE_RECORD_LENGTH) {
            throw RecordTooLargeException(
                DiskQueueConstants.RECORD_FIELD_NAME,
                packedLength,
                MAX_PACKABLE_RECORD_LENGTH
            )
        }

        val offset = fileLength
        val sink = fileHandles.appendSink()
        val written = RecordCodec.writeLive(sink, sequenceId, metaBytes, body)
        check(written == packedLength)
        sink.flush()

        fileLength += written
        liveOffsetsBySequence[sequenceId] = PackedIndexEntry.pack(written, offset)
        nextSequenceId++
        captureDiskMetadataLocked()

        QueueEntryId(sequenceId)
    }

    /** The oldest readable live entry, or `null` if the queue is empty. Corrupt head entries are
     * tombstoned so the queue cannot stall behind unreadable data. */
    suspend fun peek(): QueueEntry? = withQueueLock {
        ensureOpenLocked()
        var result: QueueEntry? = null
        while (true) {
            val (sequenceId, packed) = liveOffsetsBySequence.entries.firstOrNull() ?: break
            val entry = readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed))
            if (entry != null) {
                result = entry
                break
            }
            removeLocked(sequenceId)
        }
        result
    }

    /** Every readable live entry, oldest first. Used for inspection UIs — not a hot path. */
    suspend fun peekAll(
        outResult: MutableCollection<QueueEntry>
    ): Int = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        val before = outResult.size
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            readLiveEntryAtLocked(
                sequenceId,
                offset = PackedIndexEntry.unpackOffset(packed)
            )?.let {  outResult.add(it) }
        }
        outResult.size - before
    }

    /** A specific entry by id, or `null` if it was never enqueued or has already been removed. */
    suspend fun get(id: QueueEntryId): QueueEntry? = withQueueLock {
        ensureOpenLocked()
        val packed = liveOffsetsBySequence[id.sequenceId] ?: return@withQueueLock null
        readLiveEntryAtLocked(id.sequenceId, PackedIndexEntry.unpackOffset(packed))
    }

    /** Idempotent: removing an already-removed or unknown id is a no-op. */
    suspend fun remove(id: QueueEntryId) = withQueueLock {
        ensureOpenLocked()
        removeLocked(id.sequenceId)
        compactIfNeededLocked()
        captureDiskMetadataLocked()
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
        liveOffsetsBySequence.size
    }

    /** The first [limit] readable live entry ids, oldest first. */
    suspend fun peekIds(limit: Int, outResult: MutableCollection<QueueEntryId>): Int =
        withQueueLock {
            ensureOpenLocked()
            scrubUnreadableEntriesLocked()
            var count = 0
            for (sequenceId in liveOffsetsBySequence.keys) {
                if (count >= limit) {
                    break
                }
                val packed = liveOffsetsBySequence[sequenceId] ?: continue
                if (readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed)) != null) {
                    outResult.add(QueueEntryId(sequenceId))
                    count++
                }
            }
            count
        }

    internal suspend fun peekAllRaw(action: (Long, FrozenHttpRequestMeta, ByteArray) -> Unit) =
        withQueueLock {
            ensureOpenLocked()
            scrubUnreadableEntriesLocked()
            for ((sequenceId, packed) in liveOffsetsBySequence) {
                val entry = readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed))
                if (entry != null) {
                    action(sequenceId, entry.meta, entry.body)
                }
            }
        }

    private fun scrubUnreadableEntriesLocked() {
        scrubScratchCount = 0
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            if (readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed)) == null) {
                if (scrubScratchCount >= scrubScratch.size) {
                    scrubScratch = scrubScratch.copyOf(scrubScratch.size shl 1)
                }
                scrubScratch[scrubScratchCount++] = sequenceId
            }
        }
        for (index in 0 until scrubScratchCount) {
            removeLocked(scrubScratch[index])
        }
    }

    private fun removeLocked(targetSequenceId: Long) {
        val packed = liveOffsetsBySequence.remove(targetSequenceId) ?: return
        val removedLength = PackedIndexEntry.unpackLength(packed)

        val sink = fileHandles.appendSink()
        fileLength += RecordCodec.writeTombstone(sink, targetSequenceId)
        sink.flush()
        deadBytes += removedLength + DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    /** [sequenceId] is the id the in-memory index expects at [offset]; a record that reads back
     * with a *different* sequence id (the index pointing at the wrong offset — recovery bug,
     * manual file tampering, anything) is exactly as untrustworthy as a CRC mismatch, so it's
     * treated the same way: `null`, letting the existing scrub/tombstone path clean it up instead
     * of silently handing a caller the wrong entry's meta/body under the id they asked for. */
    private fun readLiveEntryAtLocked(sequenceId: Long, offset: Long): QueueEntry? {
        val handle = fileHandles.readHandle()
        val source = handle.source(offset).buffer()
        try {
            return when (val result = RecordCodec.readRecord(source, maxRecordFieldSize)) {
                is RecordReadResult.Live -> if (result.sequenceId == sequenceId) {
                    QueueEntry(QueueEntryId(sequenceId), result.meta, result.body)
                } else {
                    null
                }

                else -> null
            }
        } finally {
            source.close()
        }
    }

    private fun ensureOpenLocked() {
        if (opened) return

        val result = DiskQueueRecovery.recover(fileSystem, path, maxRecordFieldSize)
        liveOffsetsBySequence.clear()
        liveOffsetsBySequence.putAll(result.liveOffsetsBySequence)
        nextSequenceId = result.nextSequenceId
        deadBytes = result.deadBytes
        fileLength = result.fileLength
        opened = true
        captureDiskMetadataLocked()
    }

    private fun compactIfNeededLocked() {
        val plan = DiskQueueCompactor.planCompaction(
            fileSystem,
            path,
            maxRecordFieldSize,
            liveOffsetsBySequence,
            fileLength,
            deadBytes,
            nextSequenceId,
        ) ?: return

        fileHandles.closeAppendSink()
        fileHandles.closeReadHandle()
        fileSystem.atomicMove(plan.tempPath, path)

        liveOffsetsBySequence.clear()
        liveOffsetsBySequence.putAll(plan.liveOffsetsBySequence)
        fileLength = plan.fileLength
        deadBytes = 0L
        captureDiskMetadataLocked()
    }

    /** Throws if an operation is currently in flight on this instance, via [activeOperationCount] —
     * see that field's own doc for why the write side of that check is race-free. The check
     * itself is still best-effort, not [mutex]-grade exclusion: [close] is a plain, non-suspending
     * function on purpose (matching the `Closeable`-style contract [com.ghostserializer.sync.GhostSync]
     * needs it to satisfy), so it can't take [mutex] without either blocking a thread it shouldn't
     * or becoming suspend and breaking that contract. That leaves a narrow window between this
     * check and a brand-new operation starting — but it turns the overwhelming majority of
     * "closed while still in use" mistakes into a loud, immediate error instead of silently
     * corrupting state.
     *
     * [closed] is set *before* [RecordFileHandles.closeAll] runs, not after: if `closeAll()`
     * throws, this instance is left permanently closed rather than retryable. Deliberate, not an
     * oversight — `closeAll()` already clears its own cached handle references in a `finally`
     * regardless of whether the underlying OS-level `close()` throws (see its own doc), so the
     * handles aren't leaked either way; the alternative (`closed = true` only on success) would let
     * a caller whose first `close()` threw retry indefinitely against handles that were already
     * torn down, for no real benefit. */
    fun close() {
        if (closed) return

        check(activeOperationCount == 0) { CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE }
        closed = true
        fileHandles.closeAll()
    }
}
