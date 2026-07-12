package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.QueueEntryId

/**
 * Where [com.ghostserializer.sync.engine.GhostSyncEngine] parks requests the server rejected
 * with a 4xx — a business failure, not a transient one, so retrying it automatically in a loop
 * would just spin forever. It never blocks the main queue: [record] and [retry] both go through
 * [mainQueue], which is a separate append-only file from this queue's own [storage].
 */
class DeadLetterQueue(
    private val mainQueue: DiskQueue,
    private val storage: DiskQueue,
) {

    internal suspend fun record(method: String, url: String, headers: Map<String, String>, body: ByteArray): DeadLetterEntryId {
        val id = storage.enqueue(method, url, headers, body)
        return DeadLetterEntryId(id.sequenceId)
    }

    /** Every dead-lettered request, oldest first. Not a hot path — meant for an inspection UI. */
    suspend fun peekAll(): List<DeadLetterEntry> =
        storage.peekAll().map { entry -> DeadLetterEntry(DeadLetterEntryId(entry.id.sequenceId), entry.meta, entry.body) }

    /** Re-enqueues the entry on [mainQueue] for the next `flush()` to retry, then drops it from here. */
    suspend fun retry(id: DeadLetterEntryId) {
        val entry = storage.get(QueueEntryId(id.value)) ?: return
        mainQueue.enqueue(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
        storage.remove(QueueEntryId(id.value))
    }

    /** Drops the entry for good; its space is reclaimed on the next compaction. */
    suspend fun discard(id: DeadLetterEntryId) {
        storage.remove(QueueEntryId(id.value))
    }
}
