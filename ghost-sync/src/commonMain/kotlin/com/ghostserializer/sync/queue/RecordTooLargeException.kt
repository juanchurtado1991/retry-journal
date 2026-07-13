package com.ghostserializer.sync.queue

/**
 * Thrown by [DiskQueue.enqueue] when [body] or the encoded request metadata would exceed
 * [DiskQueueConstants.MAX_RECORD_FIELD_SIZE] on disk. That cap exists so a corrupt length field
 * is never trusted to size an allocation when a record is read back — a length past it has to be
 * rejected up front, at write time, rather than written and left unreadable: [RecordCodec] treats
 * an over-limit length the same as a corrupt one, and [DiskQueue]'s crash-recovery scan responds
 * to the first one it finds by truncating the file from that point on.
 */
class RecordTooLargeException(fieldName: String, actualSize: Int, maxSize: Int) :
    IllegalArgumentException(
        fieldName + DiskQueueConstants.RECORD_TOO_LARGE_MESSAGE_SIZE_INFIX + actualSize +
            DiskQueueConstants.RECORD_TOO_LARGE_MESSAGE_LIMIT_INFIX + maxSize +
            DiskQueueConstants.RECORD_TOO_LARGE_MESSAGE_SUFFIX,
    )
