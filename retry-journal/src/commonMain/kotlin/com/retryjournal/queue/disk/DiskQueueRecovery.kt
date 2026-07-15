package com.retryjournal.queue.disk

import com.retryjournal.queue.ReplayClaim
import com.retryjournal.queue.record.PackedIndexEntry
import com.retryjournal.queue.record.RecordScanCodec
import com.retryjournal.queue.record.RecordScanResult
import okio.BufferedSource
import okio.FileHandle
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

    fun recover(fileSystem: FileSystem, path: Path, maxRecordFieldSize: Int): DiskQueueRecoveryResult {
        if (!fileSystem.exists(path)) {
            return emptyResult()
        }

        prepareRecoveryEnvironment(fileSystem, path)

        val totalSize = fileSystem.metadata(path).size ?: 0L
        val state = DiskQueueRecoveryScanState()
        val handle = fileSystem.openReadOnly(path)
        try {
            var currentSource = handle.source(state.offset).buffer()
            val scanBuffer = ByteArray(DiskQueueConstants.SCAN_CHUNK_SIZE)
            val scanResult = RecordScanResult()

            while (state.offset < totalSize) {
                RecordScanCodec.scanRecord(currentSource, maxRecordFieldSize, scanBuffer, scanResult)
                currentSource = applyScanResult(
                    scanResult,
                    state,
                    handle,
                    currentSource,
                ) ?: break
            }
            currentSource.close()

            truncateTrailingInvalidBytes(fileSystem, path, state.lastValidOffset, totalSize)

            return DiskQueueRecoveryResult(
                state.liveOffsetsBySequence,
                state.nextSequenceId,
                state.deadBytes,
                fileLength = state.lastValidOffset,
            )
        } finally {
            handle.close()
        }
    }

    private fun emptyResult(): DiskQueueRecoveryResult =
        DiskQueueRecoveryResult(LinkedHashMap(), nextSequenceId = 0L, deadBytes = 0L, fileLength = 0L)

    private fun prepareRecoveryEnvironment(fileSystem: FileSystem, path: Path) {
        val tempPath = (path.toString() + DiskQueueConstants.COMPACTION_FILE_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)
        ReplayClaim.clearIfStale(fileSystem, ReplayClaim.claimPath(path))
    }

    private fun applyScanResult(
        scanResult: RecordScanResult,
        state: DiskQueueRecoveryScanState,
        handle: FileHandle,
        currentSource: BufferedSource,
    ): BufferedSource? = when (scanResult.type) {
        RecordScanResult.TYPE_LIVE -> {
            applyLiveScanResult(scanResult, state)
            currentSource
        }

        RecordScanResult.TYPE_TOMBSTONE -> {
            applyTombstoneScanResult(scanResult, state)
            currentSource
        }

        RecordScanResult.TYPE_INVALID -> {
            applyInvalidScanResult(scanResult, state)
            currentSource.close()
            handle.source(state.offset).buffer()
        }

        RecordScanResult.TYPE_EOF -> null
        else -> null
    }

    private fun applyLiveScanResult(scanResult: RecordScanResult, state: DiskQueueRecoveryScanState) {
        val sequenceId = scanResult.sequenceId
        val recordLength = scanResult.recordLength
        state.liveOffsetsBySequence[sequenceId] = PackedIndexEntry.pack(recordLength, state.offset)
        if (sequenceId >= state.nextSequenceId) {
            state.nextSequenceId = sequenceId + 1
        }
        state.offset += recordLength
        state.lastValidOffset = state.offset
    }

    private fun applyTombstoneScanResult(scanResult: RecordScanResult, state: DiskQueueRecoveryScanState) {
        val targetSequenceId = scanResult.sequenceId
        val recordLength = scanResult.recordLength
        val packed = state.liveOffsetsBySequence.remove(targetSequenceId)
        if (packed != null) {
            val deadLength = PackedIndexEntry.unpackLength(packed)
            state.deadBytes += deadLength + recordLength
        }

        if (targetSequenceId >= state.nextSequenceId) {
            state.nextSequenceId = targetSequenceId + 1
        }

        state.offset += recordLength
        state.lastValidOffset = state.offset
    }

    private fun applyInvalidScanResult(scanResult: RecordScanResult, state: DiskQueueRecoveryScanState) {
        val advance = if (scanResult.recordLength > 0) {
            scanResult.recordLength
        } else {
            1
        }
        state.offset += advance
        state.deadBytes += advance
    }

    private fun truncateTrailingInvalidBytes(
        fileSystem: FileSystem,
        path: Path,
        lastValidOffset: Long,
        totalSize: Long,
    ) {
        if (lastValidOffset >= totalSize) {
            return
        }
        fileSystem.openReadWrite(path).use { it.resize(lastValidOffset) }
    }
}
