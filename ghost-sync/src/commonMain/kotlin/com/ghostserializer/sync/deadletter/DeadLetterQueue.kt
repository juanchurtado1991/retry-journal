package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.DiskQueueConstants.CURRENT_DIRECTORY_PATH
import com.ghostserializer.sync.queue.DiskQueueConstants.NEWLINE_BYTE
import com.ghostserializer.sync.queue.DiskQueueConstants.RETRY_JOURNAL_SUFFIX
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.QueueEntry
import com.ghostserializer.sync.queue.QueueEntryId
import okio.utf8Size
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
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

    private class JournalData(
        val method: String,
        val url: String,
        val headers: FrozenHttpHeaders,
        val body: ByteArray,
    )

    private fun readJournal(file: Path): JournalData? {
        return try {
            storage.fileSystem.read(file) {
                val newlineIndex = indexOf(NEWLINE_BYTE.toByte())
                if (newlineIndex == -1L) {
                    return@read null
                }
                skip(newlineIndex + 1)

                val methodLen = readInt()
                val method = readUtf8(methodLen.toLong())

                val urlLen = readInt()
                val url = readUtf8(urlLen.toLong())

                val headersSize = readInt()
                val names = ArrayList<String>(headersSize)
                val values = ArrayList<String>(headersSize)
                for (i in 0 until headersSize) {
                    val kLen = readInt()
                    names.add(readUtf8(kLen.toLong()))
                    val vLen = readInt()
                    values.add(readUtf8(vLen.toLong()))
                }

                val bodyLen = readInt()
                val body = readByteArray(bodyLen.toLong())
                JournalData(method, url, FrozenHttpHeaders(names, values), body)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeJournal(file: Path, idValue: Long, entry: QueueEntry) {
        storage.fileSystem.write(file) {
            writeDecimalLong(idValue)
            writeByte(NEWLINE_BYTE)

            val method = entry.meta.method
            writeInt(method.utf8Size().toInt())
            writeUtf8(method)

            val url = entry.meta.url
            writeInt(url.utf8Size().toInt())
            writeUtf8(url)

            val headers = entry.meta.headers
            writeInt(headers.size)
            headers.forEach { name, value ->
                writeInt(name.utf8Size().toInt())
                writeUtf8(name)
                writeInt(value.utf8Size().toInt())
                writeUtf8(value)
            }

            writeInt(entry.body.size)
            write(entry.body)
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

            val journalData = readJournal(file) ?: continue
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

    private suspend fun mainQueueAlreadyContains(journalData: JournalData): Boolean {
        val pending = ArrayList<QueueEntry>()
        mainQueue.peekAll(pending)
        for (entry in pending) {
            if (entry.meta.method == journalData.method &&
                entry.meta.url == journalData.url &&
                entry.body.contentEquals(journalData.body)
            ) {
                return true
            }
        }
        return false
    }

    internal suspend fun record(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): DeadLetterEntryId {
        ensureRecovered()
        findExistingRecordId(method, url, body)?.let { return it }
        val id = storage.enqueue(method, url, headers, body)
        return DeadLetterEntryId(id.sequenceId)
    }

    /** Guards the window between this enqueue and the caller's [DiskQueue.remove] of the original
     * entry on [mainQueue] (see [com.ghostserializer.sync.engine.GhostSyncEngine.flush]): if the
     * process dies in that window, the entry is still live on the main queue, and the next
     * `flush()` replays and dead-letters it again. Recognizing it as already recorded — same
     * method, url, and body, the exact bytes being replayed — turns that into a no-op instead of
     * a duplicate entry in the dead-letter queue. */
    private suspend fun findExistingRecordId(method: String, url: String, body: ByteArray): DeadLetterEntryId? {
        var existing: DeadLetterEntryId? = null
        storage.peekAllRaw { sequenceId, meta, entryBody ->
            if (existing == null && meta.method == method && meta.url == url && entryBody.contentEquals(body)) {
                existing = DeadLetterEntryId(sequenceId)
            }
        }
        return existing
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
            writeJournal(journalFile, id.value, entry)
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
