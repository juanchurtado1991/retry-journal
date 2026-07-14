package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_PACKABLE_RECORD_LENGTH
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer

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
 * **Threading contract, two parts callers need to know:**
 * - Every suspend function here does blocking file I/O (Okio's [FileSystem] is synchronous, and
 *   so is [PlatformQueueFileLock]) on whatever thread the calling coroutine happens to be running
 *   on. Drive this class from `Dispatchers.IO` (or an equivalently blocking-friendly dispatcher;
 *   not linked here — it's declared for JVM/Native targets only, not in `commonMain`, so this
 *   file can't reference it as a resolvable symbol) — calling it from
 *   [kotlinx.coroutines.Dispatchers.Default]'s CPU-sized pool risks starving other CPU-bound work
 *   sharing that pool under real contention.
 * - [close] is **not** synchronized with [Mutex] the way every other operation is: it's a plain,
 *   non-suspending function (matching the `Closeable`-style contract
 *   [com.ghostserializer.sync.GhostSync] implements), so
 *   it can't take a suspend-only lock without either blocking a thread it shouldn't or becoming
 *   suspend itself and breaking that contract. Calling [close] while another coroutine has an
 *   operation in flight on the same instance is undefined behavior — the same caveat as closing
 *   any stream, socket, or file handle while something else is actively using it. Callers own
 *   sequencing their own shutdown so nothing is in flight when [close] runs.
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

    /** Reused across writes instead of reopened per call — [appendSinkLocked]/[closeAppendSinkLocked]. */
    private var appendSink: BufferedSink? = null
    private var readHandle: okio.FileHandle? = null

    private fun readHandleLocked(): okio.FileHandle =
        readHandle ?: fileSystem.openReadOnly(path).also { readHandle = it }

    private fun closeReadHandleLocked() {
        try {
            readHandle?.close()
        } finally {
            readHandle = null
        }
    }

    private suspend inline fun <T> withQueueLock(
        crossinline block: () -> T
    ): T = mutex.withLock {
        processLock.acquire()
        try {
            refreshIndexIfNeededLocked()
            ensureNotClosedLocked()
            block()
        } finally {
            processLock.release()
        }
    }

    private fun ensureNotClosedLocked() {
        if (closed) error(DiskQueueConstants.QUEUE_CLOSED_MESSAGE)
    }

    /** Another process may have appended while this instance held stale in-memory indexes — rescan
     * when the on-disk file size or last-modified time no longer matches what we last saw. */
    private fun refreshIndexIfNeededLocked() {
        if (!opened) {
            return
        }
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
        closeAppendSinkLocked()
        closeReadHandleLocked()
        ensureOpenLocked()
    }

    private fun captureDiskMetadataLocked() {
        if (!fileSystem.exists(path)) {
            lastKnownDiskModifiedAtMillis = null
            return
        }
        lastKnownDiskModifiedAtMillis = fileSystem.metadata(path).lastModifiedAtMillis
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
        val sink = appendSinkLocked()
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
            val (sequenceId, packed) = liveOffsetsBySequence.entries.firstOrNull()
                ?: break
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
    suspend fun peekAll(outResult: MutableCollection<QueueEntry>): Int = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        val before = outResult.size
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            val entry = readLiveEntryAtLocked(sequenceId, PackedIndexEntry.unpackOffset(packed))
            if (entry != null) {
                outResult.add(entry)
            }
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
    suspend fun remove(id: QueueEntryId) {
        withQueueLock {
            ensureOpenLocked()
            removeLocked(id.sequenceId)
            compactIfNeededLocked()
            captureDiskMetadataLocked()
        }
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

        val sink = appendSinkLocked()
        fileLength += RecordCodec.writeTombstone(sink, targetSequenceId)
        sink.flush()
        deadBytes += removedLength + DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    private fun appendSinkLocked(): BufferedSink =
        appendSink ?: fileSystem.appendingSink(path, mustExist = false).buffer()
            .also { appendSink = it }

    private fun closeAppendSinkLocked() {
        try {
            appendSink?.close()
        } finally {
            appendSink = null
        }
    }

    private fun readLiveEntryAtLocked(sequenceId: Long, offset: Long): QueueEntry? {
        val handle = readHandleLocked()
        val source = handle.source(offset).buffer()
        try {
            return when (val result = RecordCodec.readRecord(source, maxRecordFieldSize)) {
                is RecordReadResult.Live -> QueueEntry(
                    QueueEntryId(sequenceId),
                    result.meta,
                    result.body
                )

                else -> null
            }
        } finally {
            source.close()
        }
    }

    private fun ensureOpenLocked() {
        if (opened) {
            return
        }
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

        closeAppendSinkLocked()
        closeReadHandleLocked()
        fileSystem.atomicMove(plan.tempPath, path)

        liveOffsetsBySequence.clear()
        liveOffsetsBySequence.putAll(plan.liveOffsetsBySequence)
        fileLength = plan.fileLength
        deadBytes = 0L
        captureDiskMetadataLocked()
    }

    /** Not synchronized with in-flight operations on this instance — see this class's own doc
     * ("Threading contract") for why, and for the caller's responsibility here. */
    fun close() {
        if (closed) {
            return
        }
        closed = true
        try {
            closeAppendSinkLocked()
        } finally {
            closeReadHandleLocked()
        }
    }
}
