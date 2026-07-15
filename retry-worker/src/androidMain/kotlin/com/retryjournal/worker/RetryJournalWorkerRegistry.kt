package com.retryjournal.worker

import com.retryjournal.RetryJournalRuntime

/**
 * In-memory link between the [RetryJournalRuntime] an app builds once at process start and
 * [RetryJournalCoroutineWorker], which WorkManager instantiates by reflection through its default
 * `WorkerFactory` and therefore cannot receive constructor arguments from the call site.
 * [com.retryjournal.worker.setupBackgroundSync] populates this before scheduling.
 */
internal object RetryJournalWorkerRegistry {
    @Volatile
    var runtimeProvider: (() -> RetryJournalRuntime)? = null
}
