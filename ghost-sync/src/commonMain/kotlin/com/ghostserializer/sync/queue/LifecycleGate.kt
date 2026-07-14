package com.ghostserializer.sync.queue

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes [enter]/[leave] against [close] so a non-suspending [close] cannot race a new
 * operation starting between an unsynchronized in-flight check and setting the closed flag
 * (classic TOCTOU). Used by [DiskQueue], [com.ghostserializer.sync.engine.GhostSyncEngine], and
 * [com.ghostserializer.sync.client.GhostOfflineQueuePlugin].
 */
internal class LifecycleGate(
    private val closedMessage: String,
    private val closeWhileBusyMessage: String,
) {
    private val mutex = Mutex()
    private var closed = false
    private var activeCount = 0

    suspend fun enter() {
        mutex.withLock {
            if (closed) {
                error(closedMessage)
            }
            activeCount++
        }
    }

    suspend fun leave() {
        mutex.withLock {
            activeCount--
        }
    }

    /** @throws IllegalStateException when [activeCount] is still nonzero. */
    fun close() {
        runBlocking {
            mutex.withLock {
                if (closed) {
                    return@runBlocking
                }
                if (activeCount != 0) {
                    error(closeWhileBusyMessage)
                }
                closed = true
            }
        }
    }

    fun hasActiveSessions(): Boolean = runBlocking {
        mutex.withLock { activeCount > 0 }
    }
}
