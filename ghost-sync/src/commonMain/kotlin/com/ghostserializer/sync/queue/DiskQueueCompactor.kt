package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_DEAD_RATIO_THRESHOLD
import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_FILE_SUFFIX
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

    class Plan(
        val tempPath: Path,
        val liveOffsetsBySequence: LinkedHashMap<Long, Long>,
        val fileLength: Long,
    )

    fun planCompaction(
        fileSystem: FileSystem,
        path: Path,
        maxRecordFieldSize: Int,
        liveOffsetsBySequence: Map<Long, Long>,
        fileLength: Long,
        deadBytes: Long,
        nextSequenceId: Long,
    ): Plan? {
        if (fileLength <= 0L) {
            return null
        }
        val deadRatio = deadBytes.toDouble() / fileLength.toDouble()
        if (deadRatio < COMPACTION_DEAD_RATIO_THRESHOLD) {
            return null
        }

        val tempPath = (path.toString() + COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)

        val newOffsetsBySequence = LinkedHashMap<Long, Long>()
        var newOffset = 0L

        val readHandle = fileSystem.openReadOnly(path)
        try {
            fileSystem.sink(tempPath).buffer().use { sink ->
                for ((sequenceId, packed) in liveOffsetsBySequence) {
                    val offset = PackedIndexEntry.unpackOffset(packed)
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
                                newOffsetsBySequence[sequenceId] = PackedIndexEntry.pack(written, newOffset)
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

        return Plan(tempPath, newOffsetsBySequence, newOffset)
    }
}
