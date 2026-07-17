package com.retryjournal.deadletter

import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.FrozenHttpRequestMeta
import com.retryjournal.queue.LifecycleGate
import com.retryjournal.queue.QueueEntry
import com.retryjournal.queue.QueueEntryId
import com.retryjournal.queue.disk.DiskQueue
import com.retryjournal.queue.disk.DiskQueueConstants.CURRENT_DIRECTORY_PATH
import com.retryjournal.queue.disk.DiskQueueConstants.DLQ_OPS_LOCK_SUFFIX
import com.retryjournal.queue.disk.DiskQueueConstants.RETRY_JOURNAL_SUFFIX
import com.retryjournal.queue.platform.PlatformQueueFileLock
import com.retryjournal.queue.platform.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

class DeadLetterQueue(
    private val mainQueue: DiskQueue,
    private val storage: DiskQueue,
) {
    private val recoveryMutex = Mutex()
    private val retryMutex = Mutex()
    private var recovered = false
    private val lifecycleGate = LifecycleGate(
        closedMessage = CLOSED_MESSAGE,
        closeWhileBusyMessage = CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE,
    )
    private val dlqOpsProcessLock = PlatformQueueFileLock(
        lockPath = (storage.path.toString() + DLQ_OPS_LOCK_SUFFIX).toPath(),
        fileSystem = storage.fileSystem,
    )

    /** Used by [com.retryjournal.RetryJournal.close] before tearing down queues. */
    internal fun closeForShutdown() {
        lifecycleGate.close()
    }

    private suspend inline fun <T> withDlqLifecycle(
        crossinline block: suspend () -> T
    ): T {
        lifecycleGate.enter()
        try {
            return block()
        } finally {
            lifecycleGate.leave()
        }
    }

    private suspend inline fun <T> withDlqOpsProcessLock(
        crossinline block: suspend () -> T
    ): T = withContext(ioDispatcher) {
        dlqOpsProcessLock.acquire()
        try {
            block()
        } finally {
            dlqOpsProcessLock.release()
        }
    }

    private fun retryJournalPath(id: Long): Path =
        (storage.path.toString() + RETRY_JOURNAL_SUFFIX + id).toPath()

    private fun deleteDeadLetterRetryJournalIfExists(id: DeadLetterEntryId) {
        val journalFile = retryJournalPath(id.value)
        if (storage.fileSystem.exists(journalFile)) {
            storage.fileSystem.delete(journalFile)
        }
    }

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
        val journalFiles = listDeadLetterRetryJournalFiles() ?: return
        for (file in journalFiles) {
            withDlqOpsProcessLock {
                recoverSingleJournalFile(file)
            }
        }
    }

    private fun listDeadLetterRetryJournalFiles(): List<Path>? {
        val parent = storage.path.parent ?: CURRENT_DIRECTORY_PATH.toPath()
        val prefix = storage.path.name + RETRY_JOURNAL_SUFFIX
        if (!storage.fileSystem.exists(parent)) {
            return null
        }
        return storage.fileSystem.list(parent).filter { it.name.startsWith(prefix) }
    }

    private suspend fun recoverSingleJournalFile(file: Path) {
        val idValue = file.name.removePrefix(storage.path.name + RETRY_JOURNAL_SUFFIX).toLongOrNull()
        if (idValue == null) {
            storage.fileSystem.delete(file)
            return
        }
        val entryInStorage = storage.get(QueueEntryId(idValue))
        if (entryInStorage != null) {
            // Crash after journal write but before storage.remove — finish the retry.
            val journalData = DeadLetterRetryJournal.read(storage.fileSystem, file)
            if (journalData != null) {
                reEnqueueJournalDataIfMissing(journalData)
                storage.remove(QueueEntryId(idValue))
            }
            storage.fileSystem.delete(file)
            return
        }

        val journalData = DeadLetterRetryJournal.read(storage.fileSystem, file)
        if (journalData == null) {
            storage.fileSystem.delete(file)
            return
        }
        reEnqueueJournalDataIfMissing(journalData)
        storage.fileSystem.delete(file)
    }

    private suspend fun reEnqueueJournalDataIfMissing(journalData: DeadLetterRetryJournalData) {
        if (mainQueueAlreadyContains(journalData)) {
            return
        }
        mainQueue.enqueue(
            method = journalData.method,
            url = journalData.url,
            headers = journalData.headers,
            body = journalData.body,
        )
    }

    private suspend fun mainQueueAlreadyContains(journalData: DeadLetterRetryJournalData): Boolean {
        val pending = ArrayList<QueueEntry>()
        mainQueue.peekAll(pending)
        return pending.any {
            requestMatches(
                journalData.method,
                journalData.url,
                journalData.headers,
                journalData.body,
                it.meta,
                it.body,
            )
        }
    }

    internal suspend fun record(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): DeadLetterEntryId = withDlqLifecycle {
        ensureRecovered()
        retryMutex.withLock {
            withDlqOpsProcessLock {
                findExistingRecordId(method, url, headers, body)?.let { return@withDlqOpsProcessLock it }
                val id = storage.enqueue(method, url, headers, body)
                DeadLetterEntryId(id.sequenceId)
            }
        }
    }

    /** Used by [com.retryjournal.engine.HeadReplayExecutor] journal recovery. */
    internal suspend fun hasMatchingEntry(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): Boolean = withDlqLifecycle {
        ensureRecovered()
        findExistingRecordId(method, url, headers, body) != null
    }

    /** Guards the window between this enqueue and the caller's [DiskQueue.remove] of the original
     * entry on [mainQueue] (see [com.retryjournal.engine.RetryJournalEngine.flush]): if the
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
            if (existing == null &&
                requestMatches(
                    method,
                    url,
                    headers,
                    body,
                    candidateMeta = meta,
                    candidateBody = entryBody
                )
            ) {
                existing = DeadLetterEntryId(
                    value = sequenceId
                )
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
            if (!hasMatchingHeaderSlot(
                    left,
                    right,
                    leftIndex,
                    matchedRightSlots
                )
            ) {
                return false
            }
        }
        return true
    }

    private fun hasMatchingHeaderSlot(
        left: FrozenHttpHeaders,
        right: FrozenHttpHeaders,
        leftIndex: Int,
        matchedRightSlots: BooleanArray,
    ): Boolean {
        for (rightIndex in right.names.indices) {
            if (matchedRightSlots[rightIndex]) {
                continue
            }
            if (left.names[leftIndex].equals(right.names[rightIndex], ignoreCase = true) &&
                left.values[leftIndex] == right.values[rightIndex]
            ) {
                matchedRightSlots[rightIndex] = true
                return true
            }
        }
        return false
    }

    suspend fun size(): Int = withDlqLifecycle {
        ensureRecovered()
        storage.size()
    }

    suspend fun peekAll(
        outResult: MutableCollection<DeadLetterEntry>
    ): Int = withDlqLifecycle {
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
        outResult.size - before
    }

    suspend fun retry(id: DeadLetterEntryId) = withDlqLifecycle {
        ensureRecovered()
        retryMutex.withLock {
            withDlqOpsProcessLock {
                val entry = storage.get(QueueEntryId(id.value))
                    ?: return@withDlqOpsProcessLock

                val journalFile = retryJournalPath(id.value)
                DeadLetterRetryJournal.write(
                    storage.fileSystem,
                    journalFile,
                    idValue = id.value,
                    entry
                )

                storage.remove(QueueEntryId(id.value))
                try {
                    mainQueue.enqueue(
                        entry.meta.method,
                        entry.meta.url,
                        entry.meta.headers,
                        entry.body
                    )
                    storage.fileSystem.delete(journalFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    recoverSingleJournalFile(journalFile)
                }
            }
        }
    }

    suspend fun discard(id: DeadLetterEntryId) = withDlqLifecycle {
        // Delete the journal file first so ensureRecovered() doesn't see and recover it.
        deleteDeadLetterRetryJournalIfExists(id)
        // ensureRecovered() must run before dlqOpsProcessLock is acquired below, not inside it:
        // recovering a pending retry journal for a *different* id acquires the same
        // PlatformQueueFileLock again internally (see recoverPendingRetries), which isn't
        // reentrant — calling it while already holding the lock throws OverlappingFileLockException
        // (JVM) and leaks the outer FileChannel. record()/retry() already follow this order.
        ensureRecovered()
        retryMutex.withLock {
            withDlqOpsProcessLock {
                storage.remove(QueueEntryId(id.value))
            }
        }
    }

    fun close() {
        closeForShutdown()
        storage.close()
    }

    private companion object {
        const val CLOSED_MESSAGE: String = "DeadLetterQueue is closed"
        const val CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE: String =
            "Cannot close DeadLetterQueue while an operation is in flight"
    }
}
