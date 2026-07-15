# Scheduling `flush()`

retry-journal **persists** offline work and **replays** it when you call `flush()`. It does **not** watch the network by itself — you choose *when* to sync (background job on connectivity, periodic worker, "Sync now" button). That keeps the library small and lets *you* control battery and UX.

## Do it yourself

```kotlin
// A) Android: WorkManager when network is available
class SyncWorker(/* inject RetryJournal */) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        val result = retryJournal.flush()
        return if (result.stoppedEarly) Result.retry() else Result.success()
    }
}

// B) Simple polling while app is active
lifecycleScope.launch {
    while (isActive) {
        retryJournal.flush()
        delay(15.minutes)
    }
}

// C) Manual "Sync now" in settings or after ConnectivityManager callback
syncButton.setOnClickListener {
    lifecycleScope.launch {
        val r = retryJournal.flush()
        showSnackbar("Synced ${r.delivered} requests")
    }
}
```

## Or use it out of the box

Don't want to write (A)/(C) yourself? The optional **[`:retry-worker`](background-worker.md)** module implements a scheduler for you — WorkManager on Android, `BGTaskScheduler` on iOS — with one line of setup per platform.

## Multiple callers?

If the UI, a background worker, and a connectivity callback can all trigger `flush()`, see **[RetryJournalRuntime](runtime.md)** — it serializes calls so two callers never replay the same entry concurrently.

---

**Next:** [Background worker](background-worker.md) →

[← Back to docs](README.md)
