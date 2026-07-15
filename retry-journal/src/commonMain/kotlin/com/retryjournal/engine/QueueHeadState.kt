package com.retryjournal.engine

import com.retryjournal.queue.QueueEntry

/** Read-only state of the main queue head — use with [RetryJournalEngine.getHeadState]. */
sealed class QueueHeadState {
    data object Empty : QueueHeadState()

    /** Another process holds a non-stale replay claim on the FIFO head. */
    data object Blocked : QueueHeadState()

    /** Ready for HTTP replay on the next [RetryJournalEngine.flush]. */
    data class AwaitingReplay(val entry: QueueEntry) : QueueHeadState()

    /** Server side-effect recorded; next [RetryJournalEngine.flush] finishes local removal only. */
    data class AwaitingLocalRemoval(val entry: QueueEntry) : QueueHeadState()
}
