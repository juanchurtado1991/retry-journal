package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.disk.DiskQueueConstants
import com.ghostserializer.sync.queue.record.Crc32
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Per-sequence durable marker that a head entry's HTTP side-effect already succeeded but local
 * removal has not finished yet — lets [com.ghostserializer.sync.engine.HeadReplayExecutor] skip
 * the network round-trip and retry [com.ghostserializer.sync.queue.disk.DiskQueue.completeHeadReplay]
 * only.
 *
 * One file per sequence: `<queuePath>.delivery-pending.<sequenceId>`.
 */
internal object DeliveryJournal {

    const val OUTCOME_DELIVERED: String = "delivered"
    const val OUTCOME_DEAD_LETTERED: String = "dead_lettered"

    fun journalPath(queuePath: Path, sequenceId: Long): Path =
        (queuePath.toString() + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX + sequenceId).toPath()

    fun read(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
    ): DeliveryJournalReadResult {
        val path = journalPath(queuePath, sequenceId)
        if (!fileSystem.exists(path)) {
            return DeliveryJournalReadResult.Absent
        }
        return try {
            fileSystem.read(path) {
                parseContent(sequenceId)
            } ?: DeliveryJournalReadResult.Absent
        } catch (_: Exception) {
            DeliveryJournalReadResult.Absent
        }
    }

    fun hasPendingForSequence(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
    ): Boolean = read(fileSystem, queuePath, sequenceId) !is DeliveryJournalReadResult.Absent

    fun write(
        fileSystem: FileSystem,
        queuePath: Path,
        sequenceId: Long,
        outcome: String,
    ) {
        val path = journalPath(queuePath, sequenceId)
        val tempPath = (path.toString() + DiskQueueConstants.DELIVERY_JOURNAL_TEMP_SUFFIX).toPath()
        val crc = computeCrc(outcome)
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            writeUtf8(DiskQueueConstants.DELIVERY_JOURNAL_MAGIC)
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(outcome)
            writeByte(DiskQueueConstants.NEWLINE_BYTE)
            writeUtf8(crc.toString())
        }
        fileSystem.atomicMove(tempPath, path)
    }

    fun delete(fileSystem: FileSystem, queuePath: Path, sequenceId: Long) {
        fileSystem.delete(journalPath(queuePath, sequenceId), mustExist = false)
    }

    fun clearStaleJournalsLocked(
        queue: DiskQueue,
        headSequenceId: Long?,
    ) {
        val liveIds = queue.liveOffsetsBySequence.keys
        val prefix = queue.path.name + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX
        val parent = queue.path.parent ?: DiskQueueConstants.CURRENT_DIRECTORY_PATH.toPath()
        if (!queue.fileSystem.exists(parent)) {
            return
        }
        for (file in queue.fileSystem.list(parent)) {
            if (!file.name.startsWith(prefix)) {
                continue
            }
            val sequenceId = file.name.removePrefix(prefix).toLongOrNull()
            if (sequenceId == null) {
                queue.fileSystem.delete(file, mustExist = false)
            } else {
                val orphan = !liveIds.contains(sequenceId)
                val nonHead = headSequenceId != null && sequenceId != headSequenceId
                if (orphan || nonHead) {
                    queue.fileSystem.delete(file, mustExist = false)
                }
            }
        }
    }

    fun assertNoStaleJournalsLocked(
        queue: DiskQueue,
        headSequenceId: Long?,
    ) {
        val liveIds = queue.liveOffsetsBySequence.keys
        val prefix = queue.path.name + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX
        val parent = queue.path.parent ?: DiskQueueConstants.CURRENT_DIRECTORY_PATH.toPath()
        if (!queue.fileSystem.exists(parent)) {
            return
        }
        for (file in queue.fileSystem.list(parent)) {
            if (!file.name.startsWith(prefix)) {
                continue
            }
            val sequenceId = file.name.removePrefix(prefix).toLongOrNull()
            require(sequenceId != null) { "unexpected journal file ${file.name}" }
            require(liveIds.contains(sequenceId)) {
                "orphan delivery journal for sequence $sequenceId"
            }
            if (headSequenceId != null) {
                require(sequenceId == headSequenceId) {
                    "delivery journal for non-head sequence $sequenceId (head is $headSequenceId)"
                }
            }
        }
    }

    fun pendingForSequence(
        result: DeliveryJournalReadResult,
        sequenceId: Long,
    ): PendingDelivery? = when (result) {
        is DeliveryJournalReadResult.Absent -> null
        is DeliveryJournalReadResult.Valid -> PendingDelivery(sequenceId, result.outcome)
        is DeliveryJournalReadResult.CorruptPending -> PendingDelivery(sequenceId, result.outcome)
    }

    private fun BufferedSource.parseContent(expectedSequenceId: Long): DeliveryJournalReadResult? {
        val magicLineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (magicLineBreak <= 0L) {
            return null
        }
        val magic = readUtf8(magicLineBreak)
        if (magic != DiskQueueConstants.DELIVERY_JOURNAL_MAGIC) {
            return DeliveryJournalReadResult.Absent
        }
        skip(1)
        val outcomeLineBreak = indexOf(DiskQueueConstants.NEWLINE_BYTE.toByte())
        if (outcomeLineBreak <= 0L) {
            return null
        }
        val outcome = readUtf8(outcomeLineBreak)
        skip(1)
        if (exhausted()) {
            return corruptFromPartialOutcome(expectedSequenceId, outcome)
        }
        val storedCrc = readUtf8().toUIntOrNull() ?: return corruptFromPartialOutcome(expectedSequenceId, outcome)
        if (!isValidOutcome(outcome)) {
            return null
        }
        val expectedCrc = computeCrc(outcome)
        if (storedCrc != expectedCrc) {
            return DeliveryJournalReadResult.CorruptPending(expectedSequenceId, outcome)
        }
        return DeliveryJournalReadResult.Valid(outcome)
    }

    private fun corruptFromPartialOutcome(
        sequenceId: Long,
        outcome: String,
    ): DeliveryJournalReadResult? {
        if (!isValidOutcome(outcome)) {
            return null
        }
        return DeliveryJournalReadResult.CorruptPending(sequenceId, outcome)
    }

    private fun isValidOutcome(outcome: String): Boolean =
        outcome == OUTCOME_DELIVERED || outcome == OUTCOME_DEAD_LETTERED

    private fun computeCrc(outcome: String): UInt {
        val crc = Crc32.finalize(Crc32.update(Crc32.INITIAL_VALUE, outcome.encodeToByteArray()))
        return crc.toUInt()
    }
}

internal data class PendingDelivery(
    val sequenceId: Long,
    val outcome: String,
)
