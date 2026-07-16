package com.retryjournal.deadletter

import com.retryjournal.queue.disk.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
import com.retryjournal.queue.disk.DiskQueueConstants.NEWLINE_BYTE
import com.retryjournal.queue.disk.DiskQueueConstants.RETRY_JOURNAL_TEMP_SUFFIX
import com.retryjournal.queue.FrozenHttpHeaders
import com.retryjournal.queue.QueueEntry
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.utf8Size

/**
 * Reads and writes the on-disk format for a pending [DeadLetterQueue.retry] journal — the record
 * that lets a retry survive a crash between removing an entry from dead-letter storage and
 * re-enqueueing it on the main queue (see [DeadLetterQueue.retry]).
 */
internal object DeadLetterRetryJournal {

    /** A sanity cap on a journal's header count — bounds the `ArrayList(headersSize)`
     * preallocation in [read] against a corrupted count field. Generous for any real request
     * (HTTP servers/clients typically cap header counts far below this). */
    private const val MAX_HEADER_COUNT: Int = 10_000

    /** Sum of all length-prefixed fields in one journal — bounds pathological many-header OOM. */
    private const val MAX_JOURNAL_TOTAL_BYTES: Int = MAX_RECORD_FIELD_SIZE * 4

    /** Every length-prefixed field below is bounds-checked before it sizes a read or an
     * allocation: a corrupted journal (itself the product of a crash — the same one this journal
     * exists to recover from) could otherwise hand a garbage length to `readUtf8`/`readByteArray`
     * or to `ArrayList(headersSize)`. The per-field bound alone doesn't cap the *sum* across many
     * headers in one journal, so a corrupted-but-in-range header count can still add up to a real
     * OutOfMemoryError — a [Throwable], not an [Exception] — which is why the catch below is
     * [Throwable], matching [RecordCodec][com.retryjournal.queue.record.RecordCodec]'s own
     * `Ghost.deserialize` guard for the same "untrusted crash artifact, fail closed" reasoning. */
    fun read(fileSystem: FileSystem, file: Path): DeadLetterRetryJournalData? {
        return try {
            fileSystem.read(file) {
                if (!skipJournalIdLine()) {
                    return@read null
                }
                val totalBytes = ByteBudget()
                val method = readLengthPrefixedUtf8(totalBytes) ?: return@read null
                val url = readLengthPrefixedUtf8(totalBytes) ?: return@read null
                val headers = readHeadersBlock(totalBytes) ?: return@read null
                val body = readBodyBlock(totalBytes) ?: return@read null
                DeadLetterRetryJournalData(method, url, headers, body)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun write(fileSystem: FileSystem, file: Path, idValue: Long, entry: QueueEntry) {
        val tempPath = (file.toString() + RETRY_JOURNAL_TEMP_SUFFIX).toPath()
        fileSystem.delete(tempPath, mustExist = false)
        fileSystem.write(tempPath) {
            writeDecimalLong(idValue)
            writeByte(NEWLINE_BYTE)
            writeLengthPrefixedUtf8(entry.meta.method)
            writeLengthPrefixedUtf8(entry.meta.url)
            writeHeadersBlock(entry.meta.headers)
            writeInt(entry.body.size)
            write(entry.body)
        }
        fileSystem.atomicMove(tempPath, file)
    }

    private fun BufferedSource.skipJournalIdLine(): Boolean {
        val newlineIndex = indexOf(NEWLINE_BYTE.toByte())
        if (newlineIndex == -1L) {
            return false
        }
        skip(newlineIndex + 1)
        return true
    }

    private fun BufferedSource.readLengthPrefixedUtf8(
        totalBytes: ByteBudget
    ): String? {
        val length = readInt()
        totalBytes.add(length) ?: return null
        if (length !in 0..MAX_RECORD_FIELD_SIZE) {
            return null
        }
        return readUtf8(length.toLong())
    }

    private fun BufferedSource.readHeadersBlock(
        totalBytes: ByteBudget
    ): FrozenHttpHeaders? {
        val headersSize = readInt()
        if (headersSize !in 0..MAX_HEADER_COUNT) {
            return null
        }
        val names = ArrayList<String>(headersSize)
        val values = ArrayList<String>(headersSize)
        repeat(headersSize) {
            val name = readLengthPrefixedUtf8(totalBytes) ?: return null
            val value = readLengthPrefixedUtf8(totalBytes) ?: return null
            names.add(name)
            values.add(value)
        }
        return FrozenHttpHeaders(names, values)
    }

    private fun BufferedSource.readBodyBlock(totalBytes: ByteBudget): ByteArray? {
        val bodyLen = readInt()
        totalBytes.add(bodyLen) ?: return null
        if (bodyLen !in 0..MAX_RECORD_FIELD_SIZE) {
            return null
        }
        return readByteArray(bodyLen.toLong())
    }

    private class ByteBudget(private var consumed: Int = 0) {
        fun add(bytes: Int): ByteBudget? {
            consumed += bytes
            return if (consumed <= MAX_JOURNAL_TOTAL_BYTES) {
                this
            } else {
                null
            }
        }
    }

    private fun okio.BufferedSink.writeLengthPrefixedUtf8(value: String) {
        writeInt(value.utf8Size().toInt())
        writeUtf8(value)
    }

    private fun okio.BufferedSink.writeHeadersBlock(headers: FrozenHttpHeaders) {
        writeInt(headers.size)
        headers.forEach { name, value ->
            writeLengthPrefixedUtf8(name)
            writeLengthPrefixedUtf8(value)
        }
    }
}
