package com.ghostserializer.sync.queue

internal sealed class RecordReadResult {

    data class Live(
        val sequenceId: Long,
        val meta: FrozenHttpRequestMeta,
        val metaBytes: ByteArray,
        val body: ByteArray,
        val recordLength: Int,
    ) : RecordReadResult()

    data class Tombstone(
        val targetSequenceId: Long,
        val recordLength: Int,
    ) : RecordReadResult()

    /** CRC mismatch or a read ran past the end of the file mid-record — an abrupt shutdown mid-write. */
    data object Invalid : RecordReadResult()

    data object EndOfFile : RecordReadResult()
}
