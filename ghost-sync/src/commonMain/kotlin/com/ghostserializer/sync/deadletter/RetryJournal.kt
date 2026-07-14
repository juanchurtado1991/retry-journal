package com.ghostserializer.sync.deadletter

import com.ghostserializer.sync.queue.DiskQueueConstants.MAX_RECORD_FIELD_SIZE
import com.ghostserializer.sync.queue.DiskQueueConstants.NEWLINE_BYTE
import com.ghostserializer.sync.queue.FrozenHttpHeaders
import com.ghostserializer.sync.queue.QueueEntry
import okio.FileSystem
import okio.Path
import okio.utf8Size

/**
 * Reads and writes the on-disk format for a pending [DeadLetterQueue.retry] journal — the record
 * that lets a retry survive a crash between removing an entry from dead-letter storage and
 * re-enqueueing it on the main queue (see [DeadLetterQueue.retry]).
 */
internal object RetryJournal {

    class Data(
        val method: String,
        val url: String,
        val headers: FrozenHttpHeaders,
        val body: ByteArray,
    )

    /** A sanity cap on a journal's header count — bounds the `ArrayList(headersSize)`
     * preallocation in [read] against a corrupted count field. Generous for any real request
     * (HTTP servers/clients typically cap header counts far below this). */
    private const val MAX_HEADER_COUNT: Int = 10_000

    /** Every length-prefixed field below is bounds-checked before it sizes a read or an
     * allocation: a corrupted journal (itself the product of a crash — the same one this journal
     * exists to recover from) could otherwise hand a garbage length to `readUtf8`/`readByteArray`
     * or to `ArrayList(headersSize)`. A wildly negative or oversized [Int] there can throw
     * [OutOfMemoryError], which is a [Throwable], not an [Exception] — the `catch (_: Exception)`
     * below would never have caught it. */
    fun read(fileSystem: FileSystem, file: Path): Data? {
        return try {
            fileSystem.read(file) {
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
                if (headersSize !in 0..MAX_HEADER_COUNT) {
                    return@read null
                }
                val names = ArrayList<String>(headersSize)
                val values = ArrayList<String>(headersSize)
                for (i in 0 until headersSize) {
                    val headerNameLength = readInt()
                    if (headerNameLength !in 0..MAX_RECORD_FIELD_SIZE) {
                        return@read null
                    }
                    names.add(readUtf8(headerNameLength.toLong()))
                    val headerValueLength = readInt()
                    if (headerValueLength !in 0..MAX_RECORD_FIELD_SIZE) {
                        return@read null
                    }
                    values.add(readUtf8(headerValueLength.toLong()))
                }

                val bodyLen = readInt()
                if (bodyLen !in 0..MAX_RECORD_FIELD_SIZE) {
                    return@read null
                }
                val body = readByteArray(bodyLen.toLong())
                Data(method, url, FrozenHttpHeaders(names, values), body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun write(fileSystem: FileSystem, file: Path, idValue: Long, entry: QueueEntry) {
        fileSystem.write(file) {
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
}
