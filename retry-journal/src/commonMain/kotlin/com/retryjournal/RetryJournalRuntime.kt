package com.retryjournal

import com.retryjournal.engine.FlushProgress
import com.retryjournal.engine.FlushResult
import com.retryjournal.engine.RetryJournalEngine
import com.retryjournal.engine.QueueHeadState
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Lifecycle- and concurrency-aware coordinator for a [RetryJournal] instance.
 *
 * **What this is:** a single entry point for `flush()` from UI, workers, and connectivity
 * callbacks — serialized so two callers cannot replay the same head concurrently from this process.
 *
 * **What this is not:** a network observer or background scheduler. Pass an optional
 * [connectivity] [Flow] that *your app* feeds (ConnectivityManager, NWPathMonitor, health poll,
 * etc.); this class only reacts to it. WorkManager / BGTask wiring stays in your app — see
 * [retry-sample](https://github.com/juanchurtado1991/retry-journal/tree/main/retry-sample).
 *
 * Typical wiring:
 * ```
 * val runtime = RetryJournal.createRuntime(retryJournal, lifecycleScope, connectivityFlow)
 * runtime.start(autoFlushOnOnline = true)
 * // on logout / process teardown:
 * runtime.shutdown()
 * ```
 */
class RetryJournalRuntime internal constructor(
    private val retryJournal: RetryJournal?,
    private val engine: RetryJournalEngine?,
    private val replayClient: HttpClient?,
    parentScope: CoroutineScope,
    private val connectivity: Flow<Boolean>?,
    private val onShutdown: (suspend () -> Unit)?,
    private val flushMutex: Mutex,
) {
    private val supervisorJob = SupervisorJob(parentScope.coroutineContext.job)
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisorJob)

    private var started = false

    private var shutdown = false

    @Volatile
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

    /** Cancels connectivity observation jobs without closing [retryJournal]. */
    fun stop() {
        connectivityJob?.cancel()
        connectivityJob = null
        started = false
    }

    /** [stop] then [RetryJournal.close] — call on logout or process teardown.
     *
     * The close action runs *before* [shutdown] is marked `true`: if [RetryJournal.close] (or a
     * custom `onShutdown`) throws — e.g. a replay was still in flight — the exception propagates
     * and this instance is left retryable, so the caller can call [shutdown] again once whatever
     * was in flight finishes. Marking `shutdown = true` up front instead would leave every
     * resource open forever after a single failed attempt, since the early-return above would
     * skip the close action on every subsequent call. */
    suspend fun shutdown() {
        if (shutdown) {
            return
        }
        val connectivity = connectivityJob
        connectivityJob?.cancel()
        connectivityJob = null
        started = false
        connectivity?.cancelAndJoin()
        onShutdown?.let { action ->
            action()
        } ?: retryJournal?.close()
        flushMutex.withLock {
            shutdown = true
        }
        supervisorJob.cancel()
    }

    /** Serialized replay — safe to call from UI and a background worker in the same process. */
    suspend fun flush(
        onProgress: suspend (FlushProgress) -> Unit = {},
    ): FlushResult {
        ensureNotShutdown()
        return when {
            retryJournal != null -> retryJournal.flush(onProgress)
            else -> flushMutex.withLock {
                ensureNotShutdown()
                flushInternal(onProgress)
            }
        }
    }

    private suspend fun flushInternal(
        onProgress: suspend (FlushProgress) -> Unit,
    ): FlushResult = when {
        retryJournal != null -> retryJournal.flush(onProgress)
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

    /** Delegates to [com.retryjournal.engine.RetryJournalEngine.getHeadState]. */
    suspend fun getHeadState(): QueueHeadState {
        ensureNotShutdown()
        return when {
            retryJournal != null -> retryJournal.engine.getHeadState()
            else -> engine!!.getHeadState()
        }
    }

    private fun ensureNotShutdownNonSuspend() {
        if (shutdown) {
            error(RetryJournalRuntimeConstants.RUNTIME_SHUTDOWN_MESSAGE)
        }
    }

    private fun ensureNotShutdown() {
        if (shutdown) {
            error(RetryJournalRuntimeConstants.RUNTIME_ALREADY_SHUTDOWN_MESSAGE)
        }
    }

    companion object {
        /**
         * For apps that wire [RetryJournalEngine] and a replay [HttpClient] by hand instead of
         * [RetryJournal.create].
         */
        fun createForEngine(
            engine: RetryJournalEngine,
            replayClient: HttpClient,
            scope: CoroutineScope,
            connectivity: Flow<Boolean>? = null,
            onShutdown: (suspend () -> Unit)? = null,
        ): RetryJournalRuntime = RetryJournalRuntime(
            retryJournal = null,
            engine = engine,
            replayClient = replayClient,
            parentScope = scope,
            connectivity = connectivity,
            onShutdown = onShutdown ?: { engine.closeForShutdown() },
            flushMutex = Mutex(),
        )
    }
}
