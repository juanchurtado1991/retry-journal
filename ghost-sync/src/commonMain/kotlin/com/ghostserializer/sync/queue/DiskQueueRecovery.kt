package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.DiskQueueConstants.COMPACTION_FILE_SUFFIX
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * Rebuilds [DiskQueue]'s live/tombstone index by scanning its queue file from byte 0 — the path
 * that runs once per [DiskQueue] instance, the first time it's opened, and again any time another
 * process is found to have changed the file underneath it. Kept stateless and separate from
 * [DiskQueue] itself: this never touches an in-flight append sink or cached read handle, only the
 * file on disk, so it's safe to reason about (and test) independently of [DiskQueue]'s own mutable
 * bookkeeping.
 */
internal object DiskQueueRecovery {

    class Result(
        val liveOffsetsBySequence: LinkedHashMap<Long, Long>,
        val nextSequenceId: Long,
        val deadBytes: Long,
        val fileLength: Long,
    )

    fun recover(fileSystem: FileSystem, path: Path, maxRecordFieldSize: Int): Result {
        if (!fileSystem.exists(path)) {
            return Result(LinkedHashMap(), nextSequenceId = 0L, deadBytes = 0L, fileLength = 0L)
        }

        // A leftover temp file from a compaction that crashed mid-write is dead weight — the
        // original path is still the source of truth since atomicMove never ran.
        val tempPath = (path.toString() + COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)

        val liveOffsetsBySequence = LinkedHashMap<Long, Long>()
        var nextSequenceId = 0L
        var deadBytes = 0L

        val totalSize = fileSystem.metadata(path).size ?: 0L
        val handle = fileSystem.openReadOnly(path)
        try {
            var offset = 0L
            var lastValidOffset = 0L
            var currentSource = handle.source(offset).buffer()
            val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
            val scanResult = RecordScanResult()

            while (offset < totalSize) {
                RecordScanCodec.scanRecord(currentSource, maxRecordFieldSize, scanBuffer, scanResult)
                when (scanResult.type) {
                    RecordScanResult.TYPE_LIVE -> {
                        val seqId = scanResult.sequenceId
                        val len = scanResult.recordLength
                        liveOffsetsBySequence[seqId] = PackedIndexEntry.pack(len, offset)
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
                            val deadLength = PackedIndexEntry.unpackLength(packed)
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
                fileSystem.openReadWrite(path).use { it.resize(lastValidOffset) }
            }

            return Result(liveOffsetsBySequence, nextSequenceId, deadBytes, fileLength = lastValidOffset)
        } finally {
            handle.close()
        }
    }
}
