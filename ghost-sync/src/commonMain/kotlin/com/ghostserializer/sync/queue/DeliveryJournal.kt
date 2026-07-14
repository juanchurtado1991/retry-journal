package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueueConstants
import com.ghostserializer.sync.queue.record.Crc32
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Durable marker that a head entry's HTTP side-effect already succeeded but local removal has not
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

    fun read(fileSystem: FileSystem, queuePath: Path): DeliveryJournalReadResult {
        val path = journalPath(queuePath)
        if (!fileSystem.exists(path)) {
            return DeliveryJournalReadResult.Absent
        }
        return try {
            fileSystem.read(path) {
                parseContent()
            } ?: DeliveryJournalReadResult.Absent
        } catch (_: Exception) {
            DeliveryJournalReadResult.Absent
        }
    }

    fun hasPendingForSequence(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
    ): Boolean = pendingForSequence(read(fileSystem, queuePath), sequenceId) != null

    fun write(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
        outcome: String,
    ) {
        val path = journalPath(queuePath)
        val tempPath = (path.toString() + DiskQueueConstants.DELIVERY_JOURNAL_TEMP_SUFFIX).toPath()
        val crc = computeCrc(sequenceId, outcome)
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            writeUtf8(DiskQueueConstants.DELIVERY_JOURNAL_MAGIC)
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(sequenceId.toString())
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(outcome)
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(crc.toString())
        }
        fileSystem.atomicMove(tempPath, path)
    }

    fun delete(fileSystem: FileSystem, queuePath: Path) {
        fileSystem.delete(journalPath(queuePath), mustExist = false)
    }

    fun clearIfOrphan(fileSystem: FileSystem, queuePath: Path, liveSequenceIds: Set<Long>) {
        when (val result = read(fileSystem, queuePath)) {
            is DeliveryJournalReadResult.Absent -> Unit
            is DeliveryJournalReadResult.Valid -> {
                if (!liveSequenceIds.contains(result.pending.sequenceId)) {
                    delete(fileSystem, queuePath)
                }
            }
            is DeliveryJournalReadResult.CorruptPending -> {
                if (!liveSequenceIds.contains(result.sequenceId)) {
                    delete(fileSystem, queuePath)
                }
            }
        }
    }

    fun pendingForSequence(
        result: DeliveryJournalReadResult,
        sequenceId: Long,
    ): PendingDelivery? = when (result) {
        is DeliveryJournalReadResult.Absent -> null
        is DeliveryJournalReadResult.Valid -> {
            if (result.pending.sequenceId == sequenceId) {
                result.pending
            } else {
                null
            }
        }
        is DeliveryJournalReadResult.CorruptPending -> {
            if (result.sequenceId == sequenceId) {
                PendingDelivery(result.sequenceId, result.outcome)
            } else {
                null
            }
        }
    }

    private fun BufferedSource.parseContent(): DeliveryJournalReadResult? {
        val magicLineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (magicLineBreak <= 0L) {
            return null
        }
        val magic = readUtf8(magicLineBreak)
        if (magic != DiskQueueConstants.DELIVERY_JOURNAL_MAGIC) {
            return DeliveryJournalReadResult.Absent
        }
        skip(1)
        val sequenceLineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (sequenceLineBreak <= 0L) {
            return corruptFromPartialSequence(null)
        }
        val sequenceId = readUtf8(sequenceLineBreak).toLongOrNull()
        skip(1)
        val outcomeLineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (outcomeLineBreak <= 0L) {
            return corruptFromPartialSequence(sequenceId, outcome = "")
        }
        val outcome = readUtf8(outcomeLineBreak)
        skip(1)
        if (exhausted()) {
            return corruptFromPartialSequence(sequenceId, outcome)
        }
        val storedCrc = readUtf8().toUIntOrNull()
        if (sequenceId == null || storedCrc == null) {
            return corruptFromPartialSequence(sequenceId, outcome)
        }
        if (!isValidOutcome(outcome)) {
            return corruptFromPartialSequence(sequenceId, outcome)
        }
        val expectedCrc = computeCrc(sequenceId, outcome)
        if (storedCrc != expectedCrc) {
            return DeliveryJournalReadResult.CorruptPending(sequenceId, outcome)
        }
        return DeliveryJournalReadResult.Valid(PendingDelivery(sequenceId, outcome))
    }

    private fun corruptFromPartialSequence(
        sequenceId: Long?,
        outcome: String = "",
    ): DeliveryJournalReadResult? {
        if (sequenceId == null || !isValidOutcome(outcome)) {
            return null
        }
        return DeliveryJournalReadResult.CorruptPending(sequenceId, outcome)
    }

    private fun isValidOutcome(outcome: String): Boolean =
        outcome == OUTCOME_DELIVERED || outcome == OUTCOME_DEAD_LETTERED

    private fun computeCrc(sequenceId: Long, outcome: String): UInt {
        val bytes = (sequenceId.toString() + "\n" + outcome).encodeToByteArray()
        val crc = Crc32.finalize(Crc32.update(Crc32.INITIAL_VALUE, bytes))
        return crc.toUInt()
    }
}

internal data class PendingDelivery(
    val sequenceId: Long,
    val outcome: String,
)
