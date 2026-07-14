package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.disk.DiskQueueConstants
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliveryJournalTest {

    private lateinit var queuePath: okio.Path

    @BeforeTest
    fun setUp() {
        queuePath = Files.createTempDirectory("delivery-journal-test")
            .resolve("queue.bin")
            .toString()
            .toPath()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.delete(queuePath, mustExist = false)
        FileSystem.SYSTEM.delete(
            (queuePath.toString() + DiskQueueConstants.DELIVERY_JOURNAL_SUFFIX + "42").toPath(),
            mustExist = false,
        )
    }

    @Test
    fun `write read and delete round-trip per sequence`() {
        DeliveryJournal.write(FileSystem.SYSTEM, queuePath, 42L, DeliveryJournal.OUTCOME_DELIVERED)

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 42L)

        assertTrue(result is DeliveryJournalReadResult.Valid)
        assertEquals(DeliveryJournal.OUTCOME_DELIVERED, (result as DeliveryJournalReadResult.Valid).outcome)
        DeliveryJournal.delete(FileSystem.SYSTEM, queuePath, 42L)
        assertTrue(DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 42L) is DeliveryJournalReadResult.Absent)
    }

    @Test
    fun `clearStaleJournals removes a journal for a non-head sequence`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val idA = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        val idB = queue.enqueue("POST", "/b", FrozenHttpHeaders.EMPTY, "b".encodeToByteArray())
        DeliveryJournal.write(FileSystem.SYSTEM, queuePath, idB.sequenceId, DeliveryJournal.OUTCOME_DELIVERED)

        queue.prepareHeadForReplay()

        assertTrue(DeliveryJournal.read(FileSystem.SYSTEM, queuePath, idB.sequenceId) is DeliveryJournalReadResult.Absent)
        assertEquals(idA, queue.peek()?.id)
    }

    @Test
    fun `corrupt crc is treated as pending delivery for recovery`() {
        val path = DeliveryJournal.journalPath(queuePath, 99L)
        FileSystem.SYSTEM.write(path) {
            writeUtf8("ghost-sync-delivery-v1\n")
            writeUtf8("delivered\n")
            writeUtf8("0\n")
        }

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 99L)

        assertTrue(result is DeliveryJournalReadResult.CorruptPending)
        assertEquals(99L, (result as DeliveryJournalReadResult.CorruptPending).sequenceId)
    }

    @Test
    fun `partial journal without a valid outcome is treated as absent`() {
        val path = DeliveryJournal.journalPath(queuePath, 42L)
        FileSystem.SYSTEM.write(path) {
            writeUtf8("ghost-sync-delivery-v1\n")
        }

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 42L)

        assertTrue(result is DeliveryJournalReadResult.Absent)
    }

    @Test
    fun `pendingForSequence only matches the requested sequence id`() {
        DeliveryJournal.write(FileSystem.SYSTEM, queuePath, 99L, DeliveryJournal.OUTCOME_DELIVERED)
        val read = DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 99L)

        assertEquals(null, DeliveryJournal.pendingForSequence(
            DeliveryJournal.read(FileSystem.SYSTEM, queuePath, 42L),
            42L,
        ))
        assertEquals(
            PendingDelivery(99L, DeliveryJournal.OUTCOME_DELIVERED),
            DeliveryJournal.pendingForSequence(read, 99L),
        )
    }
}
