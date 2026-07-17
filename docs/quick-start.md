# Quick start

Three steps: create the queue, send requests through it, flush when you're back online.

## 1. Create `RetryJournal`

One call wires the disk queue, dead-letter store, sync engine, and Ktor client with the offline plugin:

```kotlin
val retryJournal = RetryJournal.create(
    engineFactory = CIO, // OkHttp on Android, Darwin on iOS, CIO on JVM, …
    queuePath = dataDir.resolve("retry-journal-queue.bin"),
) {
    install(ContentNegotiation) { json() } // or ghost(), etc.
    // logging, auth, timeouts — your usual HttpClient config
}
```

> [!IMPORTANT]
> **Using Ghost Serialization on iOS / Kotlin Native?**
> Since Kotlin/Native does not support reflection-based `ServiceLoader` discovery, if you install Ghost Serialization (`ghost()`), you **must** register KSP-generated registries manually at app startup (e.g. inside a shared `init` block or during iOS app launch):
> ```kotlin
> import com.ghost.serialization.Ghost
>
> Ghost.addRegistry(com.ghost.serialization.generated.GhostModuleRegistry_retry_journal.INSTANCE)
> Ghost.addRegistry(com.ghost.serialization.generated.GhostModuleRegistry_[your_module_name].INSTANCE)
> ```

## 2. Send requests with `retryJournal.client`

Use it like any Ktor client. Only **connectivity failures** (`IOException`) are queued — not HTTP 4xx/5xx from a live connection.

```kotlin
try {
    retryJournal.client.post("https://api.example.com/orders") {
        contentType(ContentType.Application.Json)
        setBody(CreateOrder("sku-123", qty = 2))
    }
    showMessage("Order sent")
} catch (_: OfflineQueuedException) {
    // Queued on disk — safe across app restarts
    showMessage("No connection — saved. We'll sync when you're back online.")
}
```

### Which requests get queued?

By default, only **mutating methods** (`POST`/`PUT`/`PATCH`/`DELETE`) are queued on a connectivity failure — a `GET`/`HEAD`/`OPTIONS` that fails just throws its `IOException` as usual, since nothing is left waiting for that response by the time a delayed `flush()` resends it days later.

Override per request with `RetryJournalHeaders.ENQUEUE_OVERRIDE` — it's stripped before the request is sent, so it never reaches the server:

```kotlin
retryJournal.client.get("https://api.example.com/mark-read") {
    header(RetryJournalHeaders.ENQUEUE_OVERRIDE, "true") // queue this GET anyway
}
```

Using a codegen client like Ktorfit instead of the raw Ktor DSL? `RetryJournalHeaders.ENQUEUE_ON_FAILURE` / `DISCARD_ON_FAILURE` are the same header pre-formatted as the full `"Name: Value"` line `@Headers` expects:

```kotlin
interface Api {
    @Headers(RetryJournalHeaders.DISCARD_ON_FAILURE)
    @POST("analytics-ping")
    suspend fun ping()

    @Headers(RetryJournalHeaders.ENQUEUE_ON_FAILURE)
    @GET("mark-read")
    suspend fun markRead()
}
```

Or replace the default rule entirely with your own global policy:

```kotlin
install(RetryJournalOfflineQueuePlugin) {
    diskQueue = queue
    shouldEnqueue = { request -> request.url.toString().contains("/orders") }
}
```

## 3. Sync when the network is back — `flush()`

Call this when **you** decide there is connectivity (see [Scheduling](scheduling.md)):

```kotlin
val result = retryJournal.flush()
// result.delivered      — sent successfully this run
// result.deadLettered   — server rejected (now in DLQ)
// result.stoppedEarly   — still offline or 5xx; run flush again later
// result.persistenceFailed — server accepted the request but local removal failed; journal left for recovery
```

## What happens on replay?

| Server / network result | What retry-journal does |
|---|---|
| **2xx Success** | Delivered — removed from the queue |
| **4xx Client error** (e.g. 400) | Moved to the [dead letter queue](dead-letters.md) — fix data or discard; no infinite retry |
| **5xx or network error again** | Stays on queue — try again on the next `flush()` |

Replay is **at-least-once**: if the server already accepted a 2xx but the app crashed before the entry was removed, the next `flush()` may send it again. Use **idempotent APIs** or server-side dedup keys for payments and other sensitive mutations.

## Inspect the queue head (optional, for UI)

Use `getHeadState()` for read-only UI — it never claims or mutates the queue:

```kotlin
when (val head = retryJournal.engine.getHeadState()) {
    QueueHeadState.Empty -> showEmpty()
    QueueHeadState.Blocked -> showSyncInProgressElsewhere()
    is QueueHeadState.AwaitingReplay -> showPending(head.entry)
    is QueueHeadState.AwaitingLocalRemoval -> showFinishingLocalRemoval(head.entry)
}
```

Replay is **only** through `flush()` — there is no manual `getEntry`/`getStatus` loop in 1.0.0.

---

**Next:** [Scheduling `flush()`](scheduling.md) →

[← Back to docs](README.md)
