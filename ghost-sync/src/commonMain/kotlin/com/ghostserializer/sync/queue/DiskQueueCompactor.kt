package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_DEAD_RATIO_THRESHOLD
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_FILE_SUFFIX
import com.ghostserializer.sync.queue.record.PackedIndexEntry
import com.ghostserializer.sync.queue.record.RecordCodec
import com.ghostserializer.sync.queue.record.RecordReadResult
import okio.BufferedSink
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Rewrites [DiskQueue]'s live records into a fresh temp file when the dead-byte ratio crosses
 * [COMPACTION_DEAD_RATIO_THRESHOLD] — never [path] itself, and never the atomic swap onto it: that
 * requires closing whatever handles [DiskQueue] itself still has open on the old file first, which
 * only [DiskQueue] knows about. [planCompaction] only ever reads [path] and writes [Plan.tempPath];
 * the caller does the swap once the [Plan] is ready.
 */
internal object DiskQueueCompactor {

    fun planCompaction(
        fileSystem: FileSystem,
        path: Path,
        maxRecordFieldSize: Int,
        liveOffsetsBySequence: Map<Long, Long>,
        fileLength: Long,
        deadBytes: Long,
        nextSequenceId: Long,
    ): DiskQueueCompactionPlan? {
        if (!shouldCompact(fileLength, deadBytes)) {
            return null
        }

        val tempPath = prepareTempCompactionFile(fileSystem, path)
        val newOffsetsBySequence = LinkedHashMap<Long, Long>()
        var newOffset = 0L

        val readHandle = fileSystem.openReadOnly(path)
        try {
            fileSystem.sink(tempPath).buffer().use { sink ->
                val rewrittenOffset = rewriteIndexedRecords(
                    readHandle,
                    sink,
                    liveOffsetsBySequence,
                    maxRecordFieldSize,
                    newOffsetsBySequence,
                    newOffset,
                ) ?: run {
                    fileSystem.delete(tempPath, mustExist = false)
                    return null
                }
                newOffset = rewrittenOffset
                newOffset = appendSequenceGapTombstoneIfNeeded(
                    sink,
                    liveOffsetsBySequence,
                    nextSequenceId,
                    newOffset,
                )
            }
        } finally {
            readHandle.close()
        }

        return DiskQueueCompactionPlan(tempPath, newOffsetsBySequence, newOffset)
    }

    private fun shouldCompact(fileLength: Long, deadBytes: Long): Boolean {
        if (fileLength <= 0L) {
            return false
        }
        val deadRatio = deadBytes.toDouble() / fileLength.toDouble()
        return deadRatio >= COMPACTION_DEAD_RATIO_THRESHOLD
    }

    private fun prepareTempCompactionFile(fileSystem: FileSystem, path: Path): Path {
        val tempPath = (path.toString() + COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)
        return tempPath
    }

    private fun rewriteIndexedRecords(
        readHandle: FileHandle,
        sink: BufferedSink,
        liveOffsetsBySequence: Map<Long, Long>,
        maxRecordFieldSize: Int,
        newOffsetsBySequence: LinkedHashMap<Long, Long>,
        startOffset: Long,
    ): Long? {
        var newOffset = startOffset
        for ((sequenceId, packed) in liveOffsetsBySequence) {
            val offset = PackedIndexEntry.unpackOffset(packed)
            newOffset = copyIndexedRecord(
                readHandle,
                sink,
                sequenceId,
                offset,
                maxRecordFieldSize,
                newOffsetsBySequence,
                newOffset,
            ) ?: return null
        }
        return newOffset
    }

    private fun copyIndexedRecord(
        readHandle: FileHandle,
        sink: BufferedSink,
        sequenceId: Long,
        offset: Long,
        maxRecordFieldSize: Int,
        newOffsetsBySequence: LinkedHashMap<Long, Long>,
        newOffset: Long,
    ): Long? {
        val source = readHandle.source(offset).buffer()
        try {
            return when (val result = RecordCodec.readRecord(source, maxRecordFieldSize)) {
                is RecordReadResult.Live -> if (result.sequenceId == sequenceId) {
                    val written = RecordCodec.writeLive(
                        sink,
                        sequenceId,
                        result.metaBytes,
                        result.body,
                    )
                    newOffsetsBySequence[sequenceId] = PackedIndexEntry.pack(written, newOffset)
                    newOffset + written
                } else {
                    null
                }

                else -> newOffset + RecordCodec.writeTombstone(sink, sequenceId)
            }
        } finally {
            source.close()
        }
    }

    private fun appendSequenceGapTombstoneIfNeeded(
        sink: BufferedSink,
        liveOffsetsBySequence: Map<Long, Long>,
        nextSequenceId: Long,
        newOffset: Long,
    ): Long {
        if (nextSequenceId <= 0 || liveOffsetsBySequence.containsKey(nextSequenceId - 1)) {
            return newOffset
        }
        return newOffset + RecordCodec.writeTombstone(sink, nextSequenceId - 1)
    }
}
