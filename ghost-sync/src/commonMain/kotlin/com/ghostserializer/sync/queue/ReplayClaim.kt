package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.platform.currentTimeMillis
import okio.BufferedSource
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

    fun claimPath(queuePath: Path): Path =
        (queuePath.toString() + DiskQueueConstants.REPLAY_CLAIM_SUFFIX).toPath()

    fun read(fileSystem: FileSystem, claimPath: Path): ReplayClaimActive? {
        if (!fileSystem.exists(claimPath)) {
            return null
        }
        return try {
            fileSystem.read(claimPath) {
                parseClaimContent()
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

    fun isStale(claim: ReplayClaimActive, nowMillis: Long): Boolean =
        claim.claimedAtMillis > nowMillis + DiskQueueConstants.REPLAY_CLAIM_CLOCK_SKEW_MILLIS ||
            nowMillis - claim.claimedAtMillis > DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS

    fun clearIfStale(fileSystem: FileSystem, claimPath: Path) {
        val claim = read(fileSystem, claimPath) ?: return
        if (isStale(claim, currentTimeMillis())) {
            delete(fileSystem, claimPath)
        }
    }

    fun isActiveClaimForSequence(
        fileSystem: FileSystem,
        claimPath: Path,
        sequenceId: Long,
        nowMillis: Long = currentTimeMillis(),
    ): Boolean {
        val claim = read(fileSystem, claimPath) ?: return false
        if (isStale(claim, nowMillis)) {
            return false
        }
        return claim.sequenceId == sequenceId
    }

    fun hasNonStaleClaim(
        fileSystem: FileSystem,
        claimPath: Path,
        nowMillis: Long = currentTimeMillis(),
    ): ReplayClaimActive? {
        val claim = read(fileSystem, claimPath) ?: return null
        if (isStale(claim, nowMillis)) {
            return null
        }
        return claim
    }

    private fun BufferedSource.parseClaimContent(): ReplayClaimActive? {
        val lineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (lineBreak <= 0L) {
            return null
        }
        val sequenceId = readUtf8(lineBreak).toLongOrNull() ?: return null
        skip(1)
        if (exhausted()) {
            return null
        }
        val claimedAtMillis = readUtf8().toLongOrNull() ?: return null
        return ReplayClaimActive(sequenceId, claimedAtMillis)
    }
}
