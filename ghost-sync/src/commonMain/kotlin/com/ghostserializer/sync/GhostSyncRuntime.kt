package com.ghostserializer.sync

import com.ghostserializer.sync.engine.FlushProgress
import com.ghostserializer.sync.engine.FlushResult
import com.ghostserializer.sync.engine.GhostSyncEngine
import com.ghostserializer.sync.engine.QueueHeadState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lifecycle- and concurrency-aware coordinator for a [GhostSync] instance.
 *
 * **What this is:** a single entry point for `flush()` from UI, workers, and connectivity
 * callbacks — serialized so two callers cannot replay the same head concurrently from this process.
 *
 * **What this is not:** a network observer or background scheduler. Pass an optional
 * [connectivity] [Flow] that *your app* feeds (ConnectivityManager, NWPathMonitor, health poll,
 * etc.); this class only reacts to it. WorkManager / BGTask wiring stays in your app — see
 * [sync-sample](https://github.com/ghostserializer/ghost-sync-kmp/tree/main/sync-sample).
 *
 * Typical wiring:
 * ```
 * val runtime = GhostSync.createRuntime(ghostSync, lifecycleScope, connectivityFlow)
 * runtime.start(autoFlushOnOnline = true)
 * // on logout / process teardown:
 * runtime.shutdown()
 * ```
 */
class GhostSyncRuntime internal constructor(
    private val ghostSync: GhostSync?,
    private val engine: GhostSyncEngine?,
    private val replayClient: HttpClient?,
    parentScope: CoroutineScope,
    private val connectivity: Flow<Boolean>?,
    private val onShutdown: (suspend () -> Unit)?,
) {
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext.job)
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)
    private val flushMutex = Mutex()

    private var started = false

    private var shutdown = false

    private var lastKnownOnline: Boolean = connectivity == null

    private var connectivityJob: Job? = null

    /** Whether [shutdown] has completed on this instance. */
    val isShutdown: Boolean
        get() = shutdown

    /** Last value observed from [connectivity], or `true` when no flow was supplied. */
    val isOnline: Boolean
        get() = lastKnownOnline

    /**
     * Begins observing [connectivity] when supplied. With [autoFlushOnOnline], calls
     * [flushWhenOnline] on each `false → true` transition.
     */
    fun start(autoFlushOnOnline: Boolean = true) {
        ensureNotShutdownNonSuspend()
        if (started) {
            return
        }
        started = true
        if (connectivity == null) {
            return
        }
        connectivityJob = scope.launch {
            connectivity.distinctUntilChanged().collect { online ->
                lastKnownOnline = online
                if (autoFlushOnOnline && online) {
                    try {
                        flushWhenOnline()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Auto-flush is best-effort — the next online signal or manual flush retries.
                    }
                }
            }
        }
    }

    /** Cancels connectivity observation jobs without closing [ghostSync]. */
    fun stop() {
        connectivityJob?.cancel()
        connectivityJob = null
        started = false
    }

    /** [stop] then [GhostSync.close] — call on logout or process teardown. */
    suspend fun shutdown() {
        if (shutdown) {
            return
        }
        stop()
        flushMutex.withLock {
            shutdown = true
        }
        supervisorJob.cancel()
        onShutdown?.let { action ->
            action()
        } ?: ghostSync?.close()
    }

    /** Serialized replay — safe to call from UI and a background worker in the same process. */
    suspend fun flush(
        onProgress: suspend (FlushProgress) -> Unit = {},
    ): FlushResult {
        ensureNotShutdown()
        return flushMutex.withLock {
            ensureNotShutdown()
            flushInternal(onProgress)
        }
    }

    private suspend fun flushInternal(
        onProgress: suspend (FlushProgress) -> Unit,
    ): FlushResult = when {
        ghostSync != null -> ghostSync.flush(onProgress)
        else -> engine!!.flush(replayClient!!, onProgress)
    }

    /** [flush] only when [isOnline] is true; returns `null` when offline (no queue mutation). */
    suspend fun flushWhenOnline(
        onProgress: suspend (FlushProgress) -> Unit = {},
    ): FlushResult? {
        ensureNotShutdown()
        if (!lastKnownOnline) {
            return null
        }
        return flush(onProgress)
    }

    /** Delegates to [com.ghostserializer.sync.engine.GhostSyncEngine.getHeadState]. */
    suspend fun getHeadState(): QueueHeadState = when {
        ghostSync != null -> ghostSync.engine.getHeadState()
        else -> engine!!.getHeadState()
    }

    private fun ensureNotShutdownNonSuspend() {
        if (shutdown) {
            error(GhostSyncRuntimeConstants.RUNTIME_SHUTDOWN_MESSAGE)
        }
    }

    private fun ensureNotShutdown() {
        if (shutdown) {
            error(GhostSyncRuntimeConstants.RUNTIME_ALREADY_SHUTDOWN_MESSAGE)
        }
    }

    companion object {
        /**
         * For apps that wire [GhostSyncEngine] and a replay [HttpClient] by hand instead of
         * [GhostSync.create].
         */
        fun createForEngine(
            engine: GhostSyncEngine,
            replayClient: HttpClient,
            scope: CoroutineScope,
            connectivity: Flow<Boolean>? = null,
            onShutdown: (suspend () -> Unit)? = null,
        ): GhostSyncRuntime = GhostSyncRuntime(
            ghostSync = null,
            engine = engine,
            replayClient = replayClient,
            parentScope = scope,
            connectivity = connectivity,
            onShutdown = onShutdown ?: { engine.closeForShutdown() },
        )
    }
}
