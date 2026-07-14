package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.platform.currentTimeMillis
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Cross-process marker that a flusher has claimed the queue head for replay — written to
 * `<queuePath>.replay-claim` while a [com.ghostserializer.sync.engine.GhostSyncEngine] is
 * mid-request on that entry. Without it, two processes sharing the same queue file (app +
 * background worker) could both `peek()` the same head, both send the HTTP request, and duplicate
 * a non-idempotent POST before either `remove()` runs.
 */
internal object ReplayClaim {

    data class Active(val sequenceId: Long, val claimedAtMillis: Long)

    fun claimPath(queuePath: Path): Path =
        (queuePath.toString() + DiskQueueConstants.REPLAY_CLAIM_SUFFIX).toPath()

    fun read(fileSystem: FileSystem, claimPath: Path): Active? {
        if (!fileSystem.exists(claimPath)) {
            return null
        }
        return try {
            fileSystem.read(claimPath) {
                val lineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
                if (lineBreak <= 0L) {
                    return@read null
                }
                val sequenceId = readUtf8(lineBreak).toLongOrNull() ?: return@read null
                skip(1)
                if (exhausted()) {
                    return@read null
                }
                val claimedAtMillis = readUtf8().toLongOrNull() ?: return@read null
                Active(sequenceId, claimedAtMillis)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun write(fileSystem: FileSystem, claimPath: Path, sequenceId: Long, claimedAtMillis: Long) {
        val tempPath = (claimPath.toString() + DiskQueueConstants.REPLAY_CLAIM_TEMP_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            writeUtf8(sequenceId.toString())
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(claimedAtMillis.toString())
        }
        fileSystem.atomicMove(tempPath, claimPath)
    }

    fun delete(fileSystem: FileSystem, claimPath: Path) {
        fileSystem.delete(claimPath, mustExist = false)
    }

    fun isStale(claim: Active, nowMillis: Long): Boolean =
        nowMillis - claim.claimedAtMillis > DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS

    fun clearIfStale(fileSystem: FileSystem, claimPath: Path) {
        val claim = read(fileSystem, claimPath) ?: return
        if (isStale(claim, currentTimeMillis())) {
            delete(fileSystem, claimPath)
        }
    }
}
