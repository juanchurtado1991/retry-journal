package com.ghostserializer.sync.queue

/** Outcome of [DiskQueue.prepareHeadForReplay] — whether [GhostSyncEngine] may send the head
 * entry over the network on this process. */
sealed class HeadReplayPrepareResult {
    data object Empty : HeadReplayPrepareResult()

    /** Another process (or a stale crash artifact) already holds an active replay claim on the
     * current head — the caller should stop early and retry later. */
    data object HeadBlocked : HeadReplayPrepareResult()

    data class Ready(val entry: QueueEntry) : HeadReplayPrepareResult()
}
