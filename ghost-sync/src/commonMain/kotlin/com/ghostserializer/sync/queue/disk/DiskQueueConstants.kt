package com.ghostserializer.sync.queue.disk

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
    const val CLOSE_WHILE_OPERATION_IN_FLIGHT_MESSAGE: String =
        "DiskQueue.close() called while an operation is still in flight on this instance. " +
            "Make sure every enqueue()/peek()/remove()/etc. call has completed before closing."

    /** [DiskQueue]'s crash-recovery scan verifies every record's CRC but never needs to keep the
     * meta/body bytes once it has — reading them in bounded chunks this size, instead of one
     * allocation the length of the whole field, keeps peak memory flat regardless of how large a
     * queued body (e.g. an image) is. */
    const val SCAN_CHUNK_SIZE: Int = 64 * 1024

    const val COMPACTION_DEAD_RATIO_THRESHOLD: Double = 0.8
    const val COMPACTION_FILE_SUFFIX: String = ".compact"
    const val LOCK_FILE_SUFFIX: String = ".lock"
    const val DLQ_OPS_LOCK_SUFFIX: String = ".dlq-ops.lock"
    const val QUEUE_GENERATION_SUFFIX: String = ".gen"
    const val QUEUE_GENERATION_TEMP_SUFFIX: String = ".gen.tmp"
    const val REPLAY_CLAIM_SUFFIX: String = ".replay-claim"
    const val REPLAY_CLAIM_TEMP_SUFFIX: String = ".replay-claim.tmp"
    const val DELIVERY_JOURNAL_SUFFIX: String = ".delivery-pending"
    const val DELIVERY_JOURNAL_TEMP_SUFFIX: String = ".delivery-pending.tmp"
    const val DELIVERY_JOURNAL_MAGIC: String = "ghost-sync-delivery-v1"

    const val COMPLETE_HEAD_NOT_HEAD_MESSAGE: String =
        "completeHeadReplay() called for an entry that is not the current queue head"
    const val COMPLETE_HEAD_CLAIM_MISSING_MESSAGE: String =
        "completeHeadReplay() called without an active replay claim — call prepareHeadForReplay() first"
    const val COMPLETE_HEAD_CLAIM_MISMATCH_MESSAGE: String =
        "completeHeadReplay() called while a different head entry is claimed for replay"
    const val COMPLETE_HEAD_CLAIM_STALE_MESSAGE: String =
        "completeHeadReplay() called with a stale replay claim"
    const val REMOVE_WHILE_CLAIMED_MESSAGE: String =
        "remove() called for an entry that is currently claimed for cross-process replay"

    /** Claims with timestamps far in the future (corrupt clock / file) are treated as stale. */
    const val REPLAY_CLAIM_CLOCK_SKEW_MILLIS: Long = 60_000L

    /** How often [GhostSyncEngine] refreshes an active [com.ghostserializer.sync.queue.ReplayClaim] while a replay HTTP
     * round-trip is in flight — keeps slow uploads from outliving the stale window. */
    const val REPLAY_CLAIM_RENEWAL_INTERVAL_MILLIS: Long = 5L * 60L * 1000L

    /** A replay claim with no [REPLAY_CLAIM_RENEWAL_INTERVAL_MILLIS] refresh for this long is
     * treated as a crash artifact and cleared — long enough for several missed renewals on a
     * bad connection, short enough that a dead process does not block the queue forever. */
    const val REPLAY_CLAIM_STALE_MILLIS: Long = 30L * 60L * 1000L

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
    const val RETRY_JOURNAL_TEMP_SUFFIX: String = ".retry.tmp."
    const val CURRENT_DIRECTORY_PATH: String = "."
}
