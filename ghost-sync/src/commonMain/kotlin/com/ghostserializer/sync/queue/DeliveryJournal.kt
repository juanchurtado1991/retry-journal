package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueueConstants
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Durable marker that a head entry's HTTP side effect already succeeded but local removal has not
 * finished yet — lets the next [com.ghostserializer.sync.engine.GhostSyncEngine.flush] skip the
 * network round-trip and retry [com.ghostserializer.sync.queue.disk.DiskQueue.completeHeadReplay]
 * only, closing the duplicate-delivery window when tombstone flush fails after a 2xx or after a
 * successful dead-letter [com.ghostserializer.sync.deadletter.DeadLetterQueue.record].
 */
internal object DeliveryJournal {

    const val OUTCOME_DELIVERED: String = "delivered"
    const val OUTCOME_DEAD_LETTERED: String = "dead_lettered"

    fun journalPath(queuePath: Path): Path =
        (queuePath.toString() + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX).toPath()

    fun read(fileSystem: FileSystem, queuePath: Path): PendingDelivery? {
        val path = journalPath(queuePath)
        if (!fileSystem.exists(path)) {
            return null
        }
        return try {
            fileSystem.read(path) {
                parseContent()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun write(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
        outcome: String,
    ) {
        val path = journalPath(queuePath)
        val tempPath = (path.toString() + DiskQueueConstants.DELIVERY_JOURNAL_TEMP_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            writeUtf8(sequenceId.toString())
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(outcome)
        }
        fileSystem.atomicMove(tempPath, path)
    }

    fun delete(fileSystem: FileSystem, queuePath: Path) {
        fileSystem.delete(journalPath(queuePath), mustExist = false)
    }

    fun clearIfOrphan(fileSystem: FileSystem, queuePath: Path, liveSequenceIds: Set<Long>) {
        val pending = read(fileSystem, queuePath) ?: return
        if (!liveSequenceIds.contains(pending.sequenceId)) {
            delete(fileSystem, queuePath)
        }
    }

    private fun BufferedSource.parseContent(): PendingDelivery? {
        val lineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (lineBreak <= 0L) {
            return null
        }
        val sequenceId = readUtf8(lineBreak).toLongOrNull() ?: return null
        skip(1)
        if (exhausted()) {
            return null
        }
        val outcome = readUtf8()
        if (outcome != OUTCOME_DELIVERED && outcome != OUTCOME_DEAD_LETTERED) {
            return null
        }
        return PendingDelivery(sequenceId, outcome)
    }
}

internal data class PendingDelivery(
    val sequenceId: Long,
    val outcome: String,
)
