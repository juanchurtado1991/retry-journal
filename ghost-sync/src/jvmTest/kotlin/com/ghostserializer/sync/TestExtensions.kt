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

/** Finds a byte marker inside a raw on-disk record — lets corruption tests flip a specific byte
 * (e.g. inside a known body) without hard-coding its offset. */
fun ByteArray.indexOfSubarray(needle: ByteArray): Int {
    outer@ for (start in 0..size - needle.size) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                continue@outer
            }
        }
        return start
    }
    error("subarray not found")
}
