package com.ghostserializer.sync.queue

import com.ghost.serialization.Ghost
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_DEAD_RATIO_THRESHOLD
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_FILE_SUFFIX
import com.ghostserializer.sync.queue.DiskQueueConstants.INDEX_OFFSET_BITS
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
import okio.use

/**
 * Append-only, crash-safe FIFO queue backed by a single file. Nothing is ever rewritten in
 * place: `enqueue()` appends a live record, `remove()` appends a tombstone referencing it
 * (never rewrites it), and compaction only ever replaces the whole file atomically. This is the
 * append-only + atomic-rename pattern proven by kmpworkmanager's own queue, deliberately avoiding
 * square/tape's in-place 16-byte header rewrite (its root corruption cause on abrupt shutdown).
 *
 * All public operations are serialized by a single [Mutex] within one process, and by an advisory
 * file lock (`<queuePath>.lock`) across processes sharing the same queue file — a foreground app
 * and a background worker in a separate process can safely open the same path.
 *
 * [peek] skips corrupt head entries (tombstoning them) so [com.ghostserializer.sync.engine.GhostSyncEngine]
 * never stalls on unreadable data; [size] and [peekIds] only count entries that can actually be read.
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
        require(maxRecordFieldSize > 0) { DiskQueueConstants.INVALID_MAX_RECORD_FIELD_SIZE_MESSAGE }
    }

    private val mutex = Mutex()
    private val processLock = PlatformQueueFileLock(
        (path.toString() + DiskQueueConstants.LOCK_FILE_SUFFIX).toPath(),
        fileSystem,
    )

    /** Sequence id -> packed length and offset (length shl 38 or offset), in FIFO order. */
    private val liveOffsetsBySequence = LinkedHashMap<Long, Long>()
    private var fileLength = 0L
    private var deadBytes = 0L
    private var nextSequenceId = 0L
    private var opened = false
    private var closed = false
    private var lastKnownDiskMtime: Long? = null

    private var scrubScratch = LongArray(8)
    private var scrubScratchCount = 0

    /** Reused across writes instead of reopened per call — [appendSinkLocked]/[closeAppendSinkLocked]. */
    private var appendSink: BufferedSink? = null
    private var readHandle: okio.FileHandle? = null

    private fun pack(length: Int, offset: Long): Long = (length.toLong() shl INDEX_OFFSET_BITS) or offset
    private fun unpackLength(packed: Long): Int = (packed ushr INDEX_OFFSET_BITS).toInt()
    private fun unpackOffset(packed: Long): Long = packed and OFFSET_MASK

    private fun readHandleLocked(): okio.FileHandle =
        readHandle ?: fileSystem.openReadOnly(path).also { readHandle = it }

    private fun closeReadHandleLocked() {
        readHandle?.close()
        readHandle = null
    }

    private suspend inline fun <T> withQueueLock(crossinline block: () -> T): T = mutex.withLock {
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
        if (closed) {
            error(DiskQueueConstants.QUEUE_CLOSED_MESSAGE)
        }
    }

    /** Another process may have appended while this instance held stale in-memory indexes — rescan
     * when the on-disk file size or last-modified time no longer matches what we last saw. */
    private fun refreshIndexIfNeededLocked() {
        if (!opened) {
            return
        }
        if (!fileSystem.exists(path)) {
            if (fileLength != 0L || lastKnownDiskMtime != null) {
                rescanFromDiskLocked()
            }
            return
        }
        val metadata = fileSystem.metadata(path)
        val diskSize = metadata.size ?: 0L
        val diskMtime = metadata.lastModifiedAtMillis
        if (diskSize == fileLength && diskMtime == lastKnownDiskMtime) {
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
            lastKnownDiskMtime = null
            return
        }
        lastKnownDiskMtime = fileSystem.metadata(path).lastModifiedAtMillis
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
            throw RecordTooLargeException(DiskQueueConstants.META_FIELD_NAME, metaBytes.size, maxRecordFieldSize)
        }
        if (body.size > maxRecordFieldSize) {
            throw RecordTooLargeException(DiskQueueConstants.BODY_FIELD_NAME, body.size, maxRecordFieldSize)
        }

        val packedLength = DiskQueueConstants.RECORD_HEADER_SIZE +
            DiskQueueConstants.SEQUENCE_FIELD_SIZE +
            DiskQueueConstants.LENGTH_FIELD_SIZE + metaBytes.size +
            DiskQueueConstants.LENGTH_FIELD_SIZE + body.size
        if (packedLength > MAX_PACKABLE_RECORD_LENGTH) {
            throw RecordTooLargeException(DiskQueueConstants.RECORD_FIELD_NAME, packedLength, MAX_PACKABLE_RECORD_LENGTH)
        }

        val offset = fileLength
        val sink = appendSinkLocked()
        val written = RecordCodec.writeLive(sink, sequenceId, metaBytes, body)
        check(written == packedLength)
        sink.flush()

        fileLength += written
        liveOffsetsBySequence[sequenceId] = pack(written, offset)
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
            val entry = readLiveEntryAtLocked(sequenceId, unpackOffset(packed))
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
            val entry = readLiveEntryAtLocked(sequenceId, unpackOffset(packed))
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
        readLiveEntryAtLocked(id.sequenceId, unpackOffset(packed))
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
    suspend fun peekIds(limit: Int, outResult: MutableCollection<QueueEntryId>): Int = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        var count = 0
        for (sequenceId in liveOffsetsBySequence.keys) {
            if (count >= limit) {
                break
            }
            val packed = liveOffsetsBySequence[sequenceId] ?: continue
            if (readLiveEntryAtLocked(sequenceId, unpackOffset(packed)) != null) {
                outResult.add(QueueEntryId(sequenceId))
                count++
            }
        }
        count
    }

    internal suspend fun peekAllRaw(action: (Long, FrozenHttpRequestMeta, ByteArray) -> Unit) = withQueueLock {
        ensureOpenLocked()
        scrubUnreadableEntriesLocked()
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            val entry = readLiveEntryAtLocked(sequenceId, unpackOffset(packed))
            if (entry != null) {
                action(sequenceId, entry.meta, entry.body)
            }
        }
    }

    private fun scrubUnreadableEntriesLocked() {
        scrubScratchCount = 0
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            if (readLiveEntryAtLocked(sequenceId, unpackOffset(packed)) == null) {
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
        val removedLength = unpackLength(packed)

        val sink = appendSinkLocked()
        fileLength += RecordCodec.writeTombstone(sink, targetSequenceId)
        sink.flush()
        deadBytes += removedLength + DiskQueueConstants.TOMBSTONE_RECORD_SIZE
    }

    private fun appendSinkLocked(): BufferedSink =
        appendSink ?: fileSystem.appendingSink(path, mustExist = false).buffer().also { appendSink = it }

    private fun closeAppendSinkLocked() {
        appendSink?.close()
        appendSink = null
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
        if (!fileSystem.exists(path)) {
            opened = true
            captureDiskMetadataLocked()
            return
        }

        val tempPath = (path.toString() + COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)

        val totalSize = fileSystem.metadata(path).size ?: 0L
        val handle = fileSystem.openReadOnly(path)
        try {
            var offset = 0L
            var lastValidOffset = 0L
            var currentSource = handle.source(offset).buffer()
            val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
            val scanResult = RecordScanResult()

            while (offset < totalSize) {
                RecordCodec.scanRecord(currentSource, maxRecordFieldSize, scanBuffer, scanResult)
                when (scanResult.type) {
                    RecordScanResult.TYPE_LIVE -> {
                        val seqId = scanResult.sequenceId
                        val len = scanResult.recordLength
                        liveOffsetsBySequence[seqId] = pack(len, offset)
                        if (seqId >= nextSequenceId) {
                            nextSequenceId = seqId + 1
                        }
                        offset += len
                        lastValidOffset = offset
                    }

                    RecordScanResult.TYPE_TOMBSTONE -> {
                        val targetSeqId = scanResult.sequenceId
                        val len = scanResult.recordLength
                        val packed = liveOffsetsBySequence.remove(targetSeqId)
                        if (packed != null) {
                            val deadLength = unpackLength(packed)
                            deadBytes += deadLength + len
                        }

                        if (targetSeqId >= nextSequenceId) {
                            nextSequenceId = targetSeqId + 1
                        }

                        offset += len
                        lastValidOffset = offset
                    }

                    RecordScanResult.TYPE_INVALID -> {
                        val advance = if (scanResult.recordLength > 0) {
                            scanResult.recordLength
                        } else {
                            1
                        }
                        offset += advance
                        deadBytes += advance
                        currentSource.close()
                        currentSource = handle.source(offset).buffer()
                    }

                    RecordScanResult.TYPE_EOF -> {
                        break
                    }
                }
            }
            currentSource.close()

            if (lastValidOffset < totalSize) {
                truncateToLocked(lastValidOffset)
            }
            fileLength = lastValidOffset
            opened = true
            captureDiskMetadataLocked()
        } finally {
            handle.close()
        }
    }

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
        var newOffset = 0L

        val readHandle = fileSystem.openReadOnly(path)
        try {
            fileSystem.sink(tempPath).buffer().use { sink ->
                for ((sequenceId, packed) in liveOffsetsBySequence) {
                    val offset = unpackOffset(packed)
                    val source = readHandle.source(offset).buffer()
                    try {
                        when (val result = RecordCodec.readRecord(source, maxRecordFieldSize)) {
                            is RecordReadResult.Live -> {
                                val written = RecordCodec.writeLive(
                                    sink,
                                    sequenceId,
                                    result.metaBytes,
                                    result.body
                                )
                                newOffsetsBySequence[sequenceId] = pack(written, newOffset)
                                newOffset += written
                            }

                            else -> {
                                val writtenTombstone = RecordCodec.writeTombstone(sink, sequenceId)
                                newOffset += writtenTombstone
                            }
                        }
                    } finally {
                        source.close()
                    }
                }

                if (nextSequenceId > 0 && !liveOffsetsBySequence.containsKey(nextSequenceId - 1)) {
                    val writtenTombstone = RecordCodec.writeTombstone(sink, nextSequenceId - 1)
                    newOffset += writtenTombstone
                }
            }
        } finally {
            readHandle.close()
        }

        closeAppendSinkLocked()
        closeReadHandleLocked()
        fileSystem.atomicMove(tempPath, path)

        liveOffsetsBySequence.clear()
        liveOffsetsBySequence.putAll(newOffsetsBySequence)
        fileLength = newOffset
        deadBytes = 0L
        captureDiskMetadataLocked()
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        closeAppendSinkLocked()
        closeReadHandleLocked()
    }

    private companion object {
        const val OFFSET_MASK: Long = (1L shl INDEX_OFFSET_BITS) - 1L
    }
}
