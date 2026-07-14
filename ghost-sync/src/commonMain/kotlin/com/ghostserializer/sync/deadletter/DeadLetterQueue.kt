package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueue
import com.ghostserializer.sync.queue.DiskQueueConstants.CURRENT_DIRECTORY_PATH
import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
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

    /** Every length-prefixed field below is bounds-checked before it sizes a read or an
     * allocation: a corrupted journal (itself the product of a crash — the same one this journal
     * exists to recover from) could otherwise hand a garbage length to `readUtf8`/`readByteArray`
     * or to `ArrayList(headersSize)`. A wildly negative or oversized [Int] there can throw
     * [OutOfMemoryError], which is a [Throwable], not an [Exception] — the `catch (_: Exception)`
     * below would never have caught it. */
    private fun readJournal(file: Path): JournalData? {
        return try {
            storage.fileSystem.read(file) {
                val newlineIndex = indexOf(NEWLINE_BYTE.toByte())
                if (newlineIndex == -1L) {
                    return@read null
                }
                skip(newlineIndex + 1)

                val methodLen = readInt()
                if (methodLen !in 0..MAX_RECORD_FIELD_SIZE) {
                    return@read null
                }
                val method = readUtf8(methodLen.toLong())

                val urlLen = readInt()
                if (urlLen !in 0..MAX_RECORD_FIELD_SIZE) {
                    return@read null
                }
                val url = readUtf8(urlLen.toLong())

                val headersSize = readInt()
                if (headersSize !in 0..MAX_JOURNAL_HEADER_COUNT) {
                    return@read null
                }
                val names = ArrayList<String>(headersSize)
                val values = ArrayList<String>(headersSize)
                for (i in 0 until headersSize) {
                    val kLen = readInt()
                    if (kLen !in 0..MAX_RECORD_FIELD_SIZE) {
                        return@read null
                    }
                    names.add(readUtf8(kLen.toLong()))
                    val vLen = readInt()
                    if (vLen !in 0..MAX_RECORD_FIELD_SIZE) {
                        return@read null
                    }
                    values.add(readUtf8(vLen.toLong()))
                }

                val bodyLen = readInt()
                if (bodyLen !in 0..MAX_RECORD_FIELD_SIZE) {
                    return@read null
                }
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

            val journalData = readJournal(file)
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

    private suspend fun mainQueueAlreadyContains(journalData: JournalData): Boolean {
        val pending = ArrayList<QueueEntry>()
        mainQueue.peekAll(pending)
        for (entry in pending) {
            if (entry.meta.method == journalData.method &&
                entry.meta.url == journalData.url &&
                entry.meta.headers == journalData.headers &&
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
        findExistingRecordId(method, url, headers, body)?.let { return it }
        val id = storage.enqueue(method, url, headers, body)
        return DeadLetterEntryId(id.sequenceId)
    }

    /** Guards the window between this enqueue and the caller's [DiskQueue.remove] of the original
     * entry on [mainQueue] (see [com.ghostserializer.sync.engine.GhostSyncEngine.flush]): if the
     * process dies in that window, the entry is still live on the main queue, and the next
     * `flush()` replays and dead-letters it again. Recognizing it as already recorded — same
     * method, url, headers, and body, the exact request being replayed — turns that into a no-op
     * instead of a duplicate entry in the dead-letter queue. Headers matter here: two requests
     * that only differ in, say, an `Authorization` header are different requests and must not
     * collapse into one dead-letter entry. */
    private suspend fun findExistingRecordId(
        method: String,
        url: String,
        headers: FrozenHttpHeaders,
        body: ByteArray,
    ): DeadLetterEntryId? {
        var existing: DeadLetterEntryId? = null
        storage.peekAllRaw { sequenceId, meta, entryBody ->
            if (existing == null &&
                meta.method == method &&
                meta.url == url &&
                meta.headers == headers &&
                entryBody.contentEquals(body)
            ) {
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

    private companion object {
        /** A sanity cap on a journal's header count — bounds the `ArrayList(headersSize)`
         * preallocation in [readJournal] against a corrupted count field. Generous for any real
         * request (HTTP servers/clients typically cap header counts far below this). */
        const val MAX_JOURNAL_HEADER_COUNT: Int = 10_000
    }
}
