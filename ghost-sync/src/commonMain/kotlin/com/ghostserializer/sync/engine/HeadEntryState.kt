package com.ghostserializer.sync.engine

import com.ghostserializer.sync.queue.QueueEntry

/** Resolved state of the FIFO head — drives [HeadReplayExecutor] transitions only. */
internal sealed class HeadEntryState {
    data object Absent : HeadEntryState()

    data object Blocked : HeadEntryState()

    data class AwaitingReplay(val entry: QueueEntry) : HeadEntryState()

    data class AwaitingLocalRemoval(
        val entry: QueueEntry,
        val outcome: DeliveryOutcome,
    ) : HeadEntryState()
}
