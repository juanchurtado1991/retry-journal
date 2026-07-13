package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_DEAD_RATIO_THRESHOLD
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_FILE_SUFFIX
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use

/**
 * Append-only, crash-safe FIFO queue backed by a single file. Nothing is ever rewritten in
 * place: `enqueue()` appends a live record, `remove()` appends a tombstone referencing it
 * (never rewrites it), and compaction only ever replaces the whole file atomically. This is the
 * append-only + atomic-rename pattern proven by kmpworkmanager's own queue, deliberately avoiding
 * square/tape's in-place 16-byte header rewrite (its root corruption cause on abrupt shutdown).
 *
 * All public operations are serialized by a single [Mutex] — the caller may invoke `enqueue()`
 * from the network thread while a scheduler-driven `flush()` concurrently calls `peek()`/`remove()`
 * from another, both within the same process. See CONVENTIONS.md for why no cross-process
 * coordination (`NSFileCoordinator`) is needed here.
 */
class DiskQueue(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
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
    private val mutex = Mutex()

    /** Sequence id -> current byte offset, in FIFO order. Offsets move under compaction; ids never do. */
    private val liveOffsetsBySequence = LinkedHashMap<Long, Long>()
    private val recordLengthsByOffset = HashMap<Long, Int>()
    private var fileLength = 0L
    private var deadBytes = 0L
    private var nextSequenceId = 0L
    private var opened = false

    /** Reused across writes instead of reopened per call — [appendSinkLocked]/[closeAppendSinkLocked]. */
    private var appendSink: BufferedSink? = null

    suspend fun enqueue(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): QueueEntryId = mutex.withLock {
        ensureOpenLocked()

        val sequenceId = nextSequenceId
        val meta = FrozenHttpRequestMeta(
            method = method,
            url = url,
            headers = headers,
            enqueuedAtMillis = currentTimeMillis(),
            attempt = 0,
        )

        val metaBytes = Ghost.encodeToBytes(meta)
        if (metaBytes.size > maxRecordFieldSize) {
            throw RecordTooLargeException(DiskQueueConstants.META_FIELD_NAME, metaBytes.size, maxRecordFieldSize)
        }
        if (body.size > maxRecordFieldSize) {
            throw RecordTooLargeException(DiskQueueConstants.BODY_FIELD_NAME, body.size, maxRecordFieldSize)
        }

        val offset = fileLength
        val sink = appendSinkLocked()
        val written = RecordCodec.writeLive(sink, sequenceId, metaBytes, body)
        sink.flush()

        fileLength += written
        recordLengthsByOffset[offset] = written
        liveOffsetsBySequence[sequenceId] = offset
        nextSequenceId++

        QueueEntryId(sequenceId)
    }

    /** The oldest live entry, or `null` if the queue is empty. Does not consume it. */
    suspend fun peek(): QueueEntry? = mutex.withLock {
        ensureOpenLocked()
        val (sequenceId, offset) = liveOffsetsBySequence.entries.firstOrNull()
            ?: return@withLock null
        readLiveEntryAtLocked(sequenceId, offset)
    }

    /** Every live entry, oldest first. Used for inspection UIs (e.g. a dead-letter list) — not a hot path. */
    suspend fun peekAll(): List<QueueEntry> = mutex.withLock {
        ensureOpenLocked()
        liveOffsetsBySequence.entries.mapNotNull { (sequenceId, offset) ->
            readLiveEntryAtLocked(
                sequenceId,
                offset
            )
        }
    }

    /** A specific entry by id, or `null` if it was never enqueued or has already been removed. */
    suspend fun get(id: QueueEntryId): QueueEntry? = mutex.withLock {
        ensureOpenLocked()
        val offset = liveOffsetsBySequence[id.sequenceId] ?: return@withLock null
        readLiveEntryAtLocked(id.sequenceId, offset)
    }

    /** Idempotent: removing an already-removed or unknown id is a no-op. */
    suspend fun remove(id: QueueEntryId) {
        mutex.withLock {
            ensureOpenLocked()
            removeLocked(id.sequenceId)
            compactIfNeededLocked()
        }
    }

    suspend fun isEmpty(): Boolean = mutex.withLock {
        ensureOpenLocked()
        liveOffsetsBySequence.isEmpty()
    }

    /** O(1): the in-memory index size, no record bytes read from disk. Safe to poll from a UI. */
    suspend fun size(): Int = mutex.withLock {
        ensureOpenLocked()
        liveOffsetsBySequence.size
    }

    /** The first [limit] live entry ids, oldest first — like [size], no record bytes are read
     * from disk to answer this, unlike [peekAll]. For a UI that wants to show individual pending
     * items (e.g. one animated chip per queued request) without paying to read every one of their
     * bodies just to render a placeholder for each. */
    suspend fun peekIds(limit: Int): List<QueueEntryId> = mutex.withLock {
        ensureOpenLocked()
        liveOffsetsBySequence.keys.take(limit).map { QueueEntryId(it) }
    }

    private fun removeLocked(targetSequenceId: Long) {
        val offset = liveOffsetsBySequence.remove(targetSequenceId) ?: return
        val removedLength = recordLengthsByOffset.remove(offset) ?: return

        val sink = appendSinkLocked()
        fileLength += RecordCodec.writeTombstone(sink, targetSequenceId)
        sink.flush()
        deadBytes += removedLength + DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    /** Opened once and kept across writes instead of reopened per `enqueue()`/`remove()` call —
     * each write still calls `flush()` right after, so a crash loses nothing this wouldn't have:
     * `close()` (the old per-call behavior) flushes too, just via a syscall this skips. Invalidated
     * by [closeAppendSinkLocked] whenever the underlying file is about to change out from under it
     * (compaction's atomic rename). */
    private fun appendSinkLocked(): BufferedSink =
        appendSink ?: fileSystem.appendingSink(path, mustExist = false).buffer().also { appendSink = it }

    private fun closeAppendSinkLocked() {
        appendSink?.close()
        appendSink = null
    }

    private fun readLiveEntryAtLocked(sequenceId: Long, offset: Long): QueueEntry? {
        val handle = fileSystem.openReadOnly(path)
        return handle.use {
            val source = it.source(offset).buffer()
            when (val result = RecordCodec.readRecord(source, maxRecordFieldSize)) {
                is RecordReadResult.Live -> QueueEntry(
                    QueueEntryId(sequenceId),
                    result.meta,
                    result.body
                )

                else -> null
            }
        }
    }

    private fun ensureOpenLocked() {
        if (opened) {
            return
        }
        if (!fileSystem.exists(path)) {
            opened = true
            return
        }

        val handle = fileSystem.openReadOnly(path)
        try {
            val source = handle.source(0L).buffer()
            var offset = 0L

            while (true) {
                when (val result = RecordCodec.scanRecord(source, maxRecordFieldSize)) {
                    is RecordScanResult.Live -> {
                        liveOffsetsBySequence[result.sequenceId] = offset
                        recordLengthsByOffset[offset] = result.recordLength
                        if (result.sequenceId >= nextSequenceId) {
                            nextSequenceId = result.sequenceId + 1
                        }
                        offset += result.recordLength
                    }

                    is RecordScanResult.Tombstone -> {
                        val deadOffset = liveOffsetsBySequence
                            .remove(result.targetSequenceId)

                        val deadLength = deadOffset?.let {
                            recordLengthsByOffset.remove(it)
                        }

                        if (deadLength != null) {
                            deadBytes += deadLength + result.recordLength
                        }

                        offset += result.recordLength
                    }

                    RecordScanResult.Invalid -> {
                        truncateToLocked(offset)
                        fileLength = offset
                        opened = true
                        return
                    }

                    RecordScanResult.EndOfFile -> {
                        fileLength = offset
                        opened = true
                        return
                    }
                }
            }
        } finally {
            handle.close()
        }
    }

    /** An abrupt shutdown mid-write leaves a partial trailing record; drop it, never fail to open. */
    private fun truncateToLocked(validLength: Long) {
        fileSystem.openReadWrite(path).use { it.resize(validLength) }
    }

    private fun compactIfNeededLocked() {
        if (fileLength <= 0L) {
            return
        }
        val deadRatio = deadBytes.toDouble() / fileLength.toDouble()
        if (deadRatio < COMPACTION_DEAD_RATIO_THRESHOLD) {
            return
        }

        val tempPath = (path.toString() + COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)

        val newOffsetsBySequence = LinkedHashMap<Long, Long>()
        val newLengthsByOffset = LinkedHashMap<Long, Int>()
        var newOffset = 0L

        val readHandle = fileSystem.openReadOnly(path)
        try {
            fileSystem.sink(tempPath).buffer().use { sink ->
                for ((sequenceId, offset) in liveOffsetsBySequence) {
                    val source = readHandle.source(offset).buffer()
                    val result = RecordCodec.readRecord(source, maxRecordFieldSize) as? RecordReadResult.Live
                        ?: continue

                    val written = RecordCodec.writeLive(
                        sink,
                        sequenceId,
                        result.metaBytes,
                        result.body
                    )

                    newOffsetsBySequence[sequenceId] = newOffset
                    newLengthsByOffset[newOffset] = written
                    newOffset += written
                }
            }
        } finally {
            readHandle.close()
        }

        // The persistent append sink (if any) still has path's pre-compaction file open — closing
        // it before the rename means the next enqueue()/remove() reopens against the compacted
        // file instead of writing into an fd for a file that no longer has that name (or, on
        // Windows, blocking the rename outright while the old handle is still open).
        closeAppendSinkLocked()
        fileSystem.atomicMove(tempPath, path)

        liveOffsetsBySequence.clear()
        liveOffsetsBySequence.putAll(newOffsetsBySequence)
        recordLengthsByOffset.clear()
        recordLengthsByOffset.putAll(newLengthsByOffset)
        fileLength = newOffset
        deadBytes = 0L
    }
}
