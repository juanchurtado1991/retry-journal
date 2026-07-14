package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.DiskQueueConstants.CURRENT_DIRECTORY_PATH
import com.ghostserializer.sync.queue.DiskQueueConstants.RETRY_JOURNAL_SUFFIX
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.FrozenHttpRequestMeta
import com.ghostserializer.sync.queue.QueueEntry
import com.ghostserializer.sync.queue.QueueEntryId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath

class DeadLetterQueue(
    private val mainQueue: DiskQueue,
    private val storage: DiskQueue,
) {
    private val recoveryMutex = Mutex()
    private val retryMutex = Mutex()
    private var recovered = false

    private suspend fun ensureRecovered() {
        if (recovered) {
            return
        }
        recoveryMutex.withLock {
            if (recovered) {
                return
            }
            recoverPendingRetries()
            recovered = true
        }
    }

    private suspend fun recoverPendingRetries() {
        val parent = storage.path.parent ?: CURRENT_DIRECTORY_PATH.toPath()
        val prefix = storage.path.name + RETRY_JOURNAL_SUFFIX
        if (!storage.fileSystem.exists(parent)) {
            return
        }
        val files = try {
            storage.fileSystem.list(parent).filter { it.name.startsWith(prefix) }
        } catch (_: Exception) {
            emptyList()
        }
        for (file in files) {
            val idValue = file.name.removePrefix(prefix).toLongOrNull() ?: continue
            val entryInStorage = storage.get(QueueEntryId(idValue))
            if (entryInStorage != null) {
                storage.fileSystem.delete(file)
                continue
            }

            val journalData = RetryJournal.read(storage.fileSystem, file)
            if (journalData == null) {
                // Unreadable now means unreadable forever — nothing about a future process
                // restart makes these same bytes parse differently. Leaving the file behind would
                // just re-attempt (and re-fail) this same read on every future startup for good.
                storage.fileSystem.delete(file)
                continue
            }
            if (!mainQueueAlreadyContains(journalData)) {
                mainQueue.enqueue(
                    method = journalData.method,
                    url = journalData.url,
                    headers = journalData.headers,
                    body = journalData.body,
                )
            }
            storage.fileSystem.delete(file)
        }
    }

    private suspend fun mainQueueAlreadyContains(journalData: RetryJournal.Data): Boolean {
        val pending = ArrayList<QueueEntry>()
        mainQueue.peekAll(pending)
        return pending.any {
            requestMatches(journalData.method, journalData.url, journalData.headers, journalData.body, it.meta, it.body)
        }
    }

    internal suspend fun record(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): DeadLetterEntryId {
        ensureRecovered()
        findExistingRecordId(method, url, headers, body)?.let { return it }
        val id = storage.enqueue(method, url, headers, body)
        return DeadLetterEntryId(id.sequenceId)
    }

    /** Guards the window between this enqueue and the caller's [DiskQueue.remove] of the original
     * entry on [mainQueue] (see [com.ghostserializer.sync.engine.GhostSyncEngine.flush]): if the
     * process dies in that window, the entry is still live on the main queue, and the next
     * `flush()` replays and dead-letters it again. Recognizing it as already recorded turns that
     * into a no-op instead of a duplicate entry — see [requestMatches] for what "already
     * recorded" means. */
    private suspend fun findExistingRecordId(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): DeadLetterEntryId? {
        var existing: DeadLetterEntryId? = null
        storage.peekAllRaw { sequenceId, meta, entryBody ->
            if (existing == null && requestMatches(method, url, headers, body, meta, entryBody)) {
                existing = DeadLetterEntryId(sequenceId)
            }
        }
        return existing
    }

    /** Shared by [mainQueueAlreadyContains] and [findExistingRecordId]: two requests are the same
     * one only if method, url, headers, *and* body all match. Headers matter — two requests that
     * only differ in, say, an `Authorization` header are different requests and must not collapse
     * into one dead-letter entry. */
    private fun requestMatches(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
        candidateMeta: FrozenHttpRequestMeta,
        candidateBody: ByteArray,
    ): Boolean = candidateMeta.method == method &&
        candidateMeta.url == url &&
        headersContentEquals(headers, candidateMeta.headers) &&
        candidateBody.contentEquals(body)

    /** Order-insensitive multiset compare — two header bundles that only differ in insertion
     * order must still dedup as the same request. */
    private fun headersContentEquals(
        left: FrozenHttpHeaders,
        right: FrozenHttpHeaders,
    ): Boolean {
        if (left.size != right.size) {
            return false
        }
        val matchedRightSlots = BooleanArray(right.size)
        for (leftIndex in left.names.indices) {
            var found = false
            for (rightIndex in right.names.indices) {
                if (matchedRightSlots[rightIndex]) {
                    continue
                }
                if (left.names[leftIndex].equals(right.names[rightIndex], ignoreCase = true) &&
                    left.values[leftIndex] == right.values[rightIndex]
                ) {
                    matchedRightSlots[rightIndex] = true
                    found = true
                    break
                }
            }
            if (!found) {
                return false
            }
        }
        return true
    }

    suspend fun size(): Int {
        ensureRecovered()
        return storage.size()
    }

    suspend fun peekAll(outResult: MutableCollection<DeadLetterEntry>): Int {
        ensureRecovered()
        val before = outResult.size
        storage.peekAllRaw { sequenceId, meta, body ->
            outResult.add(
                DeadLetterEntry(
                    DeadLetterEntryId(sequenceId),
                    meta,
                    body,
                ),
            )
        }
        return outResult.size - before
    }

    suspend fun retry(id: DeadLetterEntryId) {
        ensureRecovered()
        retryMutex.withLock {
            val entry = storage.get(QueueEntryId(id.value)) ?: return
            val journalFile = (storage.path.toString() + RETRY_JOURNAL_SUFFIX + id.value).toPath()
            RetryJournal.write(storage.fileSystem, journalFile, id.value, entry)
            storage.remove(QueueEntryId(id.value))
            mainQueue.enqueue(entry.meta.method, entry.meta.url, entry.meta.headers, entry.body)
            storage.fileSystem.delete(journalFile)
        }
    }

    suspend fun discard(id: DeadLetterEntryId) {
        ensureRecovered()
        storage.remove(QueueEntryId(id.value))
    }

    fun close() {
        storage.close()
    }
}
