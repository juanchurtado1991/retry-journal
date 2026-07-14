package com.ghostserializer.sync.queue

import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.FrozenHttpHeaders
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
        DeliveryJournal.delete(FileSystem.SYSTEM, queuePath)
    }

    @Test
    fun `write read and delete round-trip`() {
        DeliveryJournal.write(FileSystem.SYSTEM, queuePath, 42L, DeliveryJournal.OUTCOME_DELIVERED)

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath)

        assertTrue(result is DeliveryJournalReadResult.Valid)
        assertEquals(PendingDelivery(42L, DeliveryJournal.OUTCOME_DELIVERED), (result as DeliveryJournalReadResult.Valid).pending)
        DeliveryJournal.delete(FileSystem.SYSTEM, queuePath)
        assertTrue(DeliveryJournal.read(FileSystem.SYSTEM, queuePath) is DeliveryJournalReadResult.Absent)
    }

    @Test
    fun `clearIfOrphan deletes a journal for a sequence no longer in the live index`() = runBlocking {
        val queue = DiskQueue(queuePath)
        val id = queue.enqueue("POST", "/a", FrozenHttpHeaders.EMPTY, "a".encodeToByteArray())
        DeliveryJournal.write(FileSystem.SYSTEM, queuePath, id.sequenceId, DeliveryJournal.OUTCOME_DELIVERED)
        assertTrue(queue.prepareHeadForReplay() is com.ghostserializer.sync.queue.HeadReplayPrepareResult.Ready)
        queue.completeHeadReplay(id)

        DeliveryJournal.clearIfOrphan(FileSystem.SYSTEM, queuePath, emptySet())

        assertTrue(DeliveryJournal.read(FileSystem.SYSTEM, queuePath) is DeliveryJournalReadResult.Absent)
    }

    @Test
    fun `corrupt crc is treated as pending delivery for recovery`() {
        val path = DeliveryJournal.journalPath(queuePath)
        FileSystem.SYSTEM.write(path) {
            writeUtf8("ghost-sync-delivery-v1\n")
            writeUtf8("99\n")
            writeUtf8("delivered\n")
            writeUtf8("0\n")
        }

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath)

        assertTrue(result is DeliveryJournalReadResult.CorruptPending)
        assertEquals(99L, (result as DeliveryJournalReadResult.CorruptPending).sequenceId)
    }

    @Test
    fun `partial journal without a valid outcome is treated as absent`() {
        val path = DeliveryJournal.journalPath(queuePath)
        FileSystem.SYSTEM.write(path) {
            writeUtf8("ghost-sync-delivery-v1\n")
            writeUtf8("42\n")
        }

        val result = DeliveryJournal.read(FileSystem.SYSTEM, queuePath)

        assertTrue(result is DeliveryJournalReadResult.Absent)
    }
}
