package com.ghostserializer.sync.engine

import com.ghostserializer.sync.queue.QueueEntry

/** Result of inspecting the main queue head without claiming it for replay. */
sealed class QueueHeadState {
    data object Empty : QueueHeadState()

    /** Another process holds a non-stale [com.ghostserializer.sync.queue.ReplayClaim] on the head. */
    data object Blocked : QueueHeadState()

    data class Ready(val entry: QueueEntry) : QueueHeadState()
}
