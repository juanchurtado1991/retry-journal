package com.ghostserializer.sync.queue.record

import com.ghostserializer.sync.queue.FrozenHttpRequestMeta

internal sealed class RecordReadResult {

    /**
     * `equals()`/`hashCode()`/`toString()` are handwritten for the same reason as
     * [QueueEntry][com.ghostserializer.sync.queue.QueueEntry]: two `ByteArray` fields
     * ([metaBytes], [body]) that the compiler-generated versions would compare/hash by reference
     * and print as `[B@...`.
     */
    data class Live(
        val sequenceId: Long,
        val meta: FrozenHttpRequestMeta,
        val metaBytes: ByteArray,
        val body: ByteArray,
        val recordLength: Int,
    ) : RecordReadResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is Live) {
                return false
            }

            return sequenceId == other.sequenceId &&
                    meta == other.meta &&
                    metaBytes.contentEquals(other.metaBytes) &&
                    body.contentEquals(other.body) &&
                    recordLength == other.recordLength
        }

        override fun hashCode(): Int {
            var result = sequenceId.hashCode()
            result = HASH_MULTIPLIER * result + meta.hashCode()
            result = HASH_MULTIPLIER * result + metaBytes.contentHashCode()
            result = HASH_MULTIPLIER * result + body.contentHashCode()
            result = HASH_MULTIPLIER * result + recordLength
            return result
        }

        override fun toString(): String =
            "${this::class.simpleName}(sequenceId=$sequenceId, meta=$meta, metaBytes.size=${metaBytes.size}, " +
                    "body.size=${body.size}, recordLength=$recordLength)"

        private companion object {
            const val HASH_MULTIPLIER: Int = 31
        }
    }

    data class Tombstone(
        val targetSequenceId: Long,
        val recordLength: Int,
    ) : RecordReadResult()

    /** CRC mismatch or a read ran past the end of the file mid-record — an abrupt shutdown mid-write. */
    data object Invalid : RecordReadResult()

    data object EndOfFile : RecordReadResult()
}
