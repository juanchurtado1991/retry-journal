package com.ghostserializer.sync.engine

import com.ghostserializer.sync.queue.QueueEntry

/** Read-only state of the main queue head — use with [GhostSyncEngine.getHeadState]. */
sealed class QueueHeadState {
    data object Empty : QueueHeadState()

    /** Another process holds a non-stale replay claim on the FIFO head. */
    data object Blocked : QueueHeadState()

    /** Ready for HTTP replay on the next [GhostSyncEngine.flush]. */
    data class AwaitingReplay(val entry: QueueEntry) : QueueHeadState()

    /** Server side-effect recorded; next [GhostSyncEngine.flush] finishes local removal only. */
    data class AwaitingLocalRemoval(val entry: QueueEntry) : QueueHeadState()
}
