# RetryJournalRuntime (optional coordinator)

If multiple callers can trigger sync (UI button, `WorkManager`, connectivity callback), use `RetryJournalRuntime` to **serialize `flush()`** and optionally **auto-flush when your app reports online**:

```kotlin
val connectivity = callbackFlow {
    // YOUR platform code: ConnectivityManager, NWPathMonitor, health check, …
    awaitClose { }
}

val runtime = RetryJournal.createRuntime(
    retryJournal = retryJournal,
    scope = lifecycleScope,
    connectivity = connectivity, // optional — omit for manual flush only
)

runtime.start(autoFlushOnOnline = true)

// UI or worker — same entry point, concurrency-safe
runtime.flush()
runtime.flushWhenOnline() // no-op (returns null) while offline

// logout / process teardown
runtime.shutdown()
```

If you wire `RetryJournalEngine` and a replay `HttpClient` yourself (e.g. separate live vs replay clients in the sample), use `RetryJournalRuntime.createForEngine(engine, replayClient, scope, connectivity)`.

**The library does not observe the network or schedule background work** — you supply the `Flow<Boolean>`. WorkManager / BGTask stay in your app, or use [`:retry-worker`](background-worker.md) — see the [sample app](../retry-sample/README.md).

---

[← Back to docs](README.md)
