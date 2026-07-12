package com.ghostserializer.sync.queue

internal object DiskQueueConstants {
    const val KIND_FIELD_SIZE: Int = 1
    const val CRC_FIELD_SIZE: Int = 4
    const val LENGTH_FIELD_SIZE: Int = 4
    const val SEQUENCE_FIELD_SIZE: Int = 8

    const val RECORD_HEADER_SIZE: Int = KIND_FIELD_SIZE + CRC_FIELD_SIZE
    const val TOMBSTONE_RECORD_SIZE: Int = RECORD_HEADER_SIZE + SEQUENCE_FIELD_SIZE

    /** Rejects a corrupt length field before it is used to size an allocation. */
    const val MAX_RECORD_FIELD_SIZE: Int = 64 * 1024 * 1024

    const val COMPACTION_DEAD_RATIO_THRESHOLD: Double = 0.8
    const val COMPACTION_FILE_SUFFIX: String = ".compact"
}
