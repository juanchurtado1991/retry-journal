package com.retryjournal.queue.disk

import okio.Path
import okio.Path.Companion.toPath

/** Keeps [DiskQueue]'s in-memory index aligned with the on-disk file after external writes or
 * first open. Cross-process freshness uses a monotonic `.gen` sidecar — mtime+size alone can miss
 * same-length writes on filesystems with coarse timestamps. */
internal object DiskQueueIndexSync {

    fun generationPath(queuePath: Path): Path =
        (queuePath.toString() + DiskQueueConstants.QUEUE_GENERATION_SUFFIX).toPath()

    fun refreshIfNeededLocked(queue: DiskQueue) {
        if (!queue.opened) return

        if (!queue.fileSystem.exists(queue.path)) {
            if (queue.fileLength != 0L || queue.lastKnownGeneration != 0L) {
                queue.fileSystem.delete(generationPath(queue.path), mustExist = false)
                rescanFromDiskLocked(queue)
            }
            return
        }
        if (readGenerationLocked(queue) == queue.lastKnownGeneration) {
            if (onDiskFileLengthLocked(queue) == queue.fileLength) {
                return
            }
            rescanFromDiskLocked(queue)
            return
        }
        rescanFromDiskLocked(queue)
    }

    private fun onDiskFileLengthLocked(queue: DiskQueue): Long {
        if (!queue.fileSystem.exists(queue.path)) {
            return 0L
        }
        return queue.fileSystem.metadata(queue.path).size ?: 0L
    }

    fun captureMetadataLocked(queue: DiskQueue) {
        queue.lastKnownGeneration = readGenerationLocked(queue)
    }

    fun bumpGenerationLocked(queue: DiskQueue) {
        val next = readGenerationLocked(queue) + 1L
        writeGenerationLocked(queue, next)
        queue.lastKnownGeneration = next
    }

    fun ensureOpenLocked(queue: DiskQueue) {
        if (queue.opened) return

        val result = DiskQueueRecovery.recover(queue.fileSystem, queue.path, queue.maxRecordFieldSize)
        queue.liveOffsetsBySequence.replaceAllWith(result.liveOffsetsBySequence)
        queue.nextSequenceId = result.nextSequenceId
        queue.deadBytes = result.deadBytes
        queue.fileLength = result.fileLength
        queue.opened = true
        captureMetadataLocked(queue)
    }

    fun readGenerationLocked(queue: DiskQueue): Long {
        val genPath = generationPath(queue.path)
        if (!queue.fileSystem.exists(genPath)) {
            return 0L
        }
        return try {
            queue.fileSystem.read(genPath) {
                readUtf8().toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun writeGenerationLocked(queue: DiskQueue, generation: Long) {
        val genPath = generationPath(queue.path)
        val tempPath = (genPath.toString() + DiskQueueConstants.QUEUE_GENERATION_TEMP_SUFFIX).toPath()
        queue.fileSystem.delete(tempPath, mustExist = false)
        queue.fileSystem.write(tempPath) {
            writeUtf8(generation.toString())
        }
        queue.fileSystem.atomicMove(tempPath, genPath)
    }

    private fun rescanFromDiskLocked(queue: DiskQueue) {
        queue.liveOffsetsBySequence.clear()
        queue.deadBytes = 0L
        queue.nextSequenceId = 0L
        queue.fileLength = 0L
        queue.opened = false
        queue.fileHandles.closeAppendSink()
        queue.fileHandles.closeReadHandle()
        ensureOpenLocked(queue)
    }
}

internal fun DiskQueue.refreshIndexIfNeededLocked() =
    DiskQueueIndexSync.refreshIfNeededLocked(this)

internal fun DiskQueue.bumpDiskGenerationLocked() =
    DiskQueueIndexSync.bumpGenerationLocked(this)

internal fun DiskQueue.ensureOpenLocked() =
    DiskQueueIndexSync.ensureOpenLocked(this)
