package com.retryjournal.queue.record

import com.retryjournal.queue.disk.DiskQueueConstants

/**
 * Thrown by [DiskQueue.enqueue][com.retryjournal.queue.disk.DiskQueue.enqueue] when the queue
 * file has already grown past [DiskQueueConstants.MAX_PACKABLE_OFFSET] — appending here would
 * pack an offset that overflows into the record-length bits of [PackedIndexEntry], corrupting
 * both on read. Rejected up front, at write time, the same way [RecordTooLargeException]
 * rejects an over-limit field.
 */
class QueueFileTooLargeException(offset: Long) :
    IllegalStateException(
        "Queue file offset $offset exceeds ${DiskQueueConstants.MAX_PACKABLE_OFFSET}. " +
            DiskQueueConstants.QUEUE_FILE_TOO_LARGE_MESSAGE,
    )
