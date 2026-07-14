package com.ghostserializer.sync.engine

import com.ghostserializer.sync.queue.QueueEntry
import io.ktor.http.HttpStatusCode

/** Outcome of [GhostSyncEngine.getEntryAndStatus] — distinguishes an empty queue from a head
 * blocked by another process's active [com.ghostserializer.sync.queue.ReplayClaim]. */
sealed class EntryReplayResult {
    data object Empty : EntryReplayResult()

    /** Another process holds an active replay claim on the current head — retry later. */
    data object HeadBlocked : EntryReplayResult()

    /** Replay could not complete (network fault, etc.) — the claim was released. */
    data object ReplayFailed : EntryReplayResult()

    data class Ready(
        val entry: QueueEntry,
        val status: HttpStatusCode,
    ) : EntryReplayResult()
}
