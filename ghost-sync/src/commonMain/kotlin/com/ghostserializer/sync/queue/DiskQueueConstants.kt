package com.ghostserializer.sync.queue

internal object DiskQueueConstants {
    const val RECORD_KIND_LIVE_BYTE: Byte = 1
    const val RECORD_KIND_TOMBSTONE_BYTE: Byte = 2
    const val RECORD_KIND_LIVE_INT: Int = 1
    const val RECORD_KIND_TOMBSTONE_INT: Int = 2

    const val KIND_FIELD_SIZE: Int = 1
    const val CRC_FIELD_SIZE: Int = 4
    const val LENGTH_FIELD_SIZE: Int = 4
    const val SEQUENCE_FIELD_SIZE: Int = 8

    const val RECORD_HEADER_SIZE: Int = KIND_FIELD_SIZE + CRC_FIELD_SIZE
    const val TOMBSTONE_RECORD_SIZE: Int = RECORD_HEADER_SIZE + SEQUENCE_FIELD_SIZE

    /** Rejects a corrupt length field before it is used to size an allocation. */
    const val MAX_RECORD_FIELD_SIZE: Int = 64 * 1024 * 1024

    /** Live index packs record length in 26 bits (64 - [INDEX_OFFSET_BITS]). */
    const val INDEX_OFFSET_BITS: Int = 38
    const val INDEX_LENGTH_BITS: Int = 64 - INDEX_OFFSET_BITS
    const val MAX_PACKABLE_RECORD_LENGTH: Int = (1 shl INDEX_LENGTH_BITS) - 1

    const val QUEUE_CLOSED_MESSAGE: String = "DiskQueue is closed"
    const val INVALID_MAX_RECORD_FIELD_SIZE_MESSAGE: String = "maxRecordFieldSize must be positive"

    /** [DiskQueue]'s crash-recovery scan verifies every record's CRC but never needs to keep the
     * meta/body bytes once it has — reading them in bounded chunks this size, instead of one
     * allocation the length of the whole field, keeps peak memory flat regardless of how large a
     * queued body (e.g. an image) is. */
    const val SCAN_CHUNK_SIZE: Int = 64 * 1024

    const val COMPACTION_DEAD_RATIO_THRESHOLD: Double = 0.8
    const val COMPACTION_FILE_SUFFIX: String = ".compact"
    const val LOCK_FILE_SUFFIX: String = ".lock"
    const val LOCK_ACQUIRE_FAILED_MESSAGE: String = "Failed to acquire exclusive queue file lock"

    const val META_FIELD_NAME: String = "meta"
    const val BODY_FIELD_NAME: String = "body"
    const val RECORD_FIELD_NAME: String = "record"

    const val RECORD_TOO_LARGE_MESSAGE_SIZE_INFIX: String = " is "
    const val RECORD_TOO_LARGE_MESSAGE_LIMIT_INFIX: String = " bytes, over the "
    const val RECORD_TOO_LARGE_MESSAGE_SUFFIX: String =
        "-byte limit (DiskQueueConstants.MAX_RECORD_FIELD_SIZE) — refusing to write a record that could never be read back"
    const val RECORD_EOF_PREMATURE_MESSAGE: String = "Reached EOF prematurely while reading payload chunk"
    const val NEWLINE_BYTE: Int = 10
    const val RETRY_JOURNAL_SUFFIX: String = ".retry."
    const val CURRENT_DIRECTORY_PATH: String = "."
}
