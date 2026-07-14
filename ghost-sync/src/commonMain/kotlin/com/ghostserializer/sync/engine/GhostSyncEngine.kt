package com.ghostserializer.sync.engine

import com.ghostserializer.sync.deadletter.DeadLetterQueue
import com.ghostserializer.sync.queue.disk.DiskQueue
import com.ghostserializer.sync.queue.LifecycleGate
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drains [queue] through [HeadReplayExecutor] — the only public replay entry point in 1.0.0.
 * Call [flush] to replay queued HTTP mutations; use [getHeadState] for read-only UI inspection.
 */
class GhostSyncEngine(
    private val queue: DiskQueue,
    private val deadLetterQueue: DeadLetterQueue,
) {
    private val executor = HeadReplayExecutor(queue, deadLetterQueue, HttpReplayer())
    private val replayMutex = Mutex()
    private val lifecycleGate = LifecycleGate(
        closedMessage = SyncEngineConstants.ENGINE_CLOSED_MESSAGE,
        closeWhileBusyMessage = SyncEngineConstants.CLOSE_WHILE_REPLAY_IN_FLIGHT_MESSAGE,
    )

    /** Used by [com.ghostserializer.sync.GhostSync.close] before tearing down clients. */
    internal fun closeForShutdown() {
        lifecycleGate.close()
    }

    /** Replays the FIFO head until empty, blocked by another process, or stopped on 5xx/offline. */
    suspend fun flush(
        client: HttpClient,
        onProgress: suspend (FlushProgress) -> Unit = {},
    ): FlushResult {
        val progressBuffer = mutableListOf<FlushProgress>()
        val result = replayMutex.withLock {
            withReplayLifecycle {
                executor.drain(client) { progressBuffer += it }
            }
        }
        progressBuffer.forEach { onProgress(it) }
        return result
    }

    /** Read-only head inspection for UI — never claims or mutates the queue. */
    suspend fun getHeadState(): QueueHeadState = replayMutex.withLock {
        withReplayLifecycle {
            when (val state = executor.resolveHeadState()) {
                HeadEntryState.Absent -> QueueHeadState.Empty
                HeadEntryState.Blocked -> QueueHeadState.Blocked
                is HeadEntryState.AwaitingReplay -> QueueHeadState.AwaitingReplay(state.entry)
                is HeadEntryState.AwaitingLocalRemoval -> QueueHeadState.AwaitingLocalRemoval(state.entry)
            }
        }
    }

    private suspend inline fun <T> withReplayLifecycle(crossinline block: suspend () -> T): T {
        lifecycleGate.enter()
        try {
            return block()
        } finally {
            lifecycleGate.leave()
        }
    }
}
