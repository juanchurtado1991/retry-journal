package com.retryjournal.queue

import com.retryjournal.queue.disk.DiskQueueConstants
import com.retryjournal.queue.platform.currentTimeMillis
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Cross-process marker that a flusher has claimed the queue head for replay — written to
 * `<queuePath>.replay-claim` while a [com.retryjournal.engine.RetryJournalEngine] is
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

    /** A claim whose timestamp is ahead of [nowMillis] by more than the allowed skew is either (a)
     * a modest backward jump of the wall clock since the claim was written — NTP correction,
     * resuming a suspended device, a manual clock change; `claimedAtMillis`/`nowMillis` both come
     * from [com.retryjournal.queue.platform.currentTimeMillis], which has no monotonicity guarantee
     * — or (b) a genuinely corrupt claim file. Both used to be treated identically (stale,
     * discard), but that's wrong for (a): the claim may well still be active, and releasing it lets
     * another process replay the same head entry, risking a duplicated non-idempotent POST — the
     * exact thing [ReplayClaim] exists to prevent. Distinguishing them by how far ahead the
     * timestamp is: within [DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS] of now, treat it as (a)
     * and keep the claim active (fail safe against a duplicate send); beyond that, it's
     * implausible for any real clock correction and is treated as (b), same as before, so a
     * genuinely corrupt claim still self-heals instead of blocking the queue forever. */
    fun isStale(claim: ReplayClaimActive, nowMillis: Long): Boolean {
        val age = nowMillis - claim.claimedAtMillis
        if (age > DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS) {
            return true
        }
        if (-age > DiskQueueConstants.REPLAY_CLAIM_STALE_MILLIS) {
            return true
        }
        return false
    }

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
