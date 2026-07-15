package com.retryjournal.engine

import com.retryjournal.queue.QueueEntryId

/**
 * Reported once per queue entry as [RetryJournalEngine.flush] processes it, in the same order the
 * entries were originally enqueued — lets a caller (e.g. a UI showing the queue draining in real
 * time) react as each request actually resolves, instead of only seeing a single aggregate
 * [FlushResult] once the whole queue is done.
 */
sealed class FlushProgress {
    data class Delivered(val id: QueueEntryId) : FlushProgress()
    data class DeadLettered(val id: QueueEntryId) : FlushProgress()
}
