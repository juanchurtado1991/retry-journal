package com.ghostserializer.sync

import com.ghostserializer.sync.deadletter.DeadLetterEntry
import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.QueueEntry
import com.ghostserializer.sync.queue.QueueEntryId
import kotlinx.coroutines.runBlocking

fun DiskQueue.peekAll(): List<QueueEntry> = ArrayList<QueueEntry>().also {
    runBlocking { peekAll(it) }
}

fun DiskQueue.peekIds(limit: Int): List<QueueEntryId> = ArrayList<QueueEntryId>().also {
    runBlocking { peekIds(limit, it) }
}

fun DeadLetterQueue.peekAll(): List<DeadLetterEntry> = ArrayList<DeadLetterEntry>().also {
    runBlocking { peekAll(it) }
}
