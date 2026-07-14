# 👻 ghost-sync

**Never lose an HTTP request because the network dropped.**

Offline-first HTTP sync for **Kotlin Multiplatform** — Android, iOS, and JVM. Your user taps “Send” on a mountain with no signal? The request is **saved to disk**. When Wi‑Fi comes back and your app runs sync, it **goes out exactly as they sent it** — JSON, photos, multipart uploads, all of it.

No general-purpose database required for the sync pipeline — ghost-sync is a dedicated append-only queue. Works with **Ghost**, **kotlinx.serialization**, or any Ktor body you already use.

---

## The problem you already know

| Without ghost-sync | With ghost-sync |
|---|---|
| User offline → request fails, data lost or manual retry UX | User offline → request **queued on disk**, clear “saved for later” UX |
| App killed mid-request → maybe nothing persisted | **Crash-safe** append-only file + CRC recovery |
| Background worker + UI both sync → duplicate POSTs | **Cross-process locks** + replay claims |
| Server returns 400 → infinite retry loop | **Dead letter queue** — inspect, retry, or discard |

---

## Real-world flow (e.g. hiking, subway, airplane mode)

```
1. User has no signal
      ↓
2. They post an order or upload a photo with ghostSync.client
      ↓
3. Network throws → plugin saves method, URL, headers, body bytes to disk
      ↓
4. App catches OfflineQueuedException → show “Saved — will sync when online”
      ↓
5. User gets Wi‑Fi again
      ↓
6. YOUR APP calls ghostSync.flush()  ← you wire this (WorkManager, NetworkCallback, etc.)
      ↓
7. Requests replay in order → 2xx removes from queue, done ✓
```

**Important:** ghost-sync **persists** offline work and **replays** it when you call `flush()`. It does **not** watch the network by itself — you choose *when* to sync (background job on connectivity, periodic worker, “Sync now” button). That keeps the library small and lets *you* control battery and UX.

See the [sample app](sync-sample/README.md) for a working demo (toggle server off → upload → toggle on → sync).

---

## What happens on replay?

| Server / network result | What ghost-sync does |
|---|---|
| **2xx Success** | Delivered — removed from the queue |
| **4xx Client error** (e.g. 400) | Moved to **Dead Letter Queue** — fix data or discard; no infinite retry |
| **5xx or network error again** | Stays on queue — try again on the next `flush()` |

Replay is **at-least-once**: if the server already accepted a 2xx but the app crashed before the entry was removed, the next `flush()` may send it again. Use **idempotent APIs** or server-side dedup keys for payments and other sensitive mutations.

---

## Features

- **Dedicated append-only queue file** — purpose-built FIFO HTTP sync pipeline (not a general-purpose DB; Room KMP is great for app state, ghost-sync owns the offline mutation queue)
- **Any serializer** — your JSON layer is separate; the queue stores raw wire bytes
- **Files & multipart** — image uploads captured offline, replayed byte-for-byte
- **Crash-safe** — CRC32 on every record; recovery after partial writes
- **Dead Letter Queue** — UI-friendly list of server-rejected requests
- **Cross-process safe** — app + background worker can share one queue file
- **Scheduler-agnostic** — call `flush()` from WorkManager, coroutines, or a button

---

## Not Tape, not Room — an offline HTTP sync stack

Libraries like [Square Tape](https://github.com/square/tape) solve a **different** problem: a generic `byte[]` FIFO on disk (`QueueFile` / `ObjectQueue`). You still build capture, retry policy, HTTP replay, dead letters, and multi-process coordination yourself.

**ghost-sync is the sync layer**, not a queue primitive:

| Tape-style file queue | ghost-sync |
|---|---|
| Arbitrary `byte[]` blobs | Frozen HTTP requests — method, URL, headers, body bytes |
| Android / Java | **KMP** — Android, iOS, JVM |
| You wire networking & retry policy | **Ktor plugin** captures on `IOException` + **replay engine** applies 2xx / 4xx DLQ / 5xx retry |
| In-place header rewrites (Tape's own docs warn about corruption on conventional filesystems) | **Append-only** records + atomic rename — no in-place header mutation |
| Single-process assumption | **Cross-process** file locks + replay claims (app + WorkManager) |
| No delivery semantics | **Per-sequence delivery journal** — two-phase commit so a crash after server 2xx doesn't force a duplicate POST |

Room KMP is great for **app state** (users, settings, cached reads). ghost-sync owns the **offline mutation queue** — the ordered pipe of POSTs/PUTs that must survive no signal and replay when you're back.

Tape is a fine brick if you want to build all of the above from scratch. ghost-sync gives you the stack; you only decide **when** to call `flush()`.

---

## Installation

```kotlin
// libs.versions.toml
[libraries]
ghost-sync = { module = "com.ghostserializer:ghost-sync", version = "1.0.0" }
```

```kotlin
// build.gradle.kts (shared module)
dependencies {
    implementation(libs.ghost.sync)
}
```

**Targets:** `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`.

> **iOS:** Kotlin/Native targets build on macOS. Checklist for device verification: [`ios_techdebt.md`](ios_techdebt.md).

---

## Quick start (3 steps)

### 1. Create `GhostSync`

One call wires the disk queue, dead-letter store, sync engine, and Ktor client with the offline plugin:

```kotlin
val ghostSync = GhostSync.create(
    engineFactory = CIO, // OkHttp on Android, Darwin on iOS, CIO on JVM, …
    queuePath = dataDir.resolve("ghost-sync-queue.bin"),
) {
    install(ContentNegotiation) { json() } // or ghost(), etc.
    // logging, auth, timeouts — your usual HttpClient config
}
```

### 2. Send requests with `ghostSync.client`

Use it like any Ktor client. Only **connectivity failures** (`IOException`) are queued — not HTTP 4xx/5xx from a live connection.

```kotlin
try {
    ghostSync.client.post("https://api.example.com/orders") {
        contentType(ContentType.Application.Json)
        setBody(CreateOrder("sku-123", qty = 2))
    }
    showMessage("Order sent")
} catch (_: OfflineQueuedException) {
    // Queued on disk — safe across app restarts
    showMessage("No connection — saved. We'll sync when you're back online.")
}
```

### 3. Sync when the network is back — `flush()`

Call this when **you** decide there is connectivity (see [Scheduling](#scheduling-flush-when-wifi-returns) below):

```kotlin
val result = ghostSync.flush()
// result.delivered      — sent successfully this run
// result.deadLettered   — server rejected (now in DLQ)
// result.stoppedEarly   — still offline or 5xx; run flush again later
// result.persistenceFailed — server accepted the request but local removal failed; journal left for recovery
```

### Inspect the queue head (UI)

Use `getHeadState()` for read-only UI — it never claims or mutates the queue:

```kotlin
when (val head = ghostSync.engine.getHeadState()) {
    QueueHeadState.Empty -> showEmpty()
    QueueHeadState.Blocked -> showSyncInProgressElsewhere()
    is QueueHeadState.AwaitingReplay -> showPending(head.entry)
    is QueueHeadState.AwaitingLocalRemoval -> showFinishingLocalRemoval(head.entry)
}
```

Replay is **only** through `flush()` — there is no manual `getEntry`/`getStatus` loop in 1.0.0.

---

## Scheduling flush when Wi‑Fi returns

This is the piece every production app needs. Examples:

```kotlin
// A) Android: WorkManager when network is available
class SyncWorker(/* inject GhostSync */) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        val result = ghostSync.flush()
        return if (result.stoppedEarly) Result.retry() else Result.success()
    }
}

// B) Simple polling while app is active
lifecycleScope.launch {
    while (isActive) {
        ghostSync.flush()
        delay(15.minutes)
    }
}

// C) Manual “Sync now” in settings or after ConnectivityManager callback
syncButton.setOnClickListener {
    lifecycleScope.launch {
        val r = ghostSync.flush()
        showSnackbar("Synced ${r.delivered} requests")
    }
}
```

The [sync-sample](sync-sample/README.md) uses **kmpworkmanager** so sync can run in a background worker — copy that pattern or use your own.

---

## GhostSyncRuntime (optional coordinator)

If multiple callers can trigger sync (UI button, `WorkManager`, connectivity callback), use
`GhostSyncRuntime` to **serialize `flush()`** and optionally **auto-flush when your app reports
online**:

```kotlin
val connectivity = callbackFlow {
    // YOUR platform code: ConnectivityManager, NWPathMonitor, health check, …
    awaitClose { }
}

val runtime = GhostSync.createRuntime(
    ghostSync = ghostSync,
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

If you wire [GhostSyncEngine] and a replay [HttpClient] yourself (e.g. separate live vs replay
clients in the sample), use `GhostSyncRuntime.createForEngine(engine, replayClient, scope, connectivity)`.

**The library does not observe the network or schedule background work** — you supply the
`Flow<Boolean>`. WorkManager / BGTask stay in your app (see [sync-sample](sync-sample/README.md)).

---

## File & image uploads offline

The plugin captures **whatever bytes** Ktor would have sent — including `MultiPartFormDataContent`:

```kotlin
ghostSync.client.post("https://api.example.com/upload") {
    setBody(
        MultiPartFormDataContent(
            formData {
                append("photo", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"trail.jpg\"")
                })
            }
        )
    )
}
```

Same `OfflineQueuedException` flow — when `flush()` runs on Wi‑Fi, the upload is replayed.

**Size limit:** each queued meta/body field defaults to **64 MiB** (`maxRecordFieldSize` in `GhostSync.create`). Larger uploads fail closed with `BodyCaptureException` instead of silently truncating.

---

## Dead letters (server said no)

When replay gets a **non-retry-worthy 4xx**, the request moves to the dead letter queue instead of blocking everything behind it:

```kotlin
val failed = mutableListOf<DeadLetterEntry>()
ghostSync.deadLetterQueue.peekAll(failed)

// Let the user fix and retry
ghostSync.deadLetterQueue.retry(failed.first().id)

// Or discard permanently
ghostSync.deadLetterQueue.discard(failed.first().id)
```

Build a “Failed uploads” or “Sync issues” screen from `peekAll()`.

---

## What you build vs what the library gives you

| You provide | ghost-sync provides |
|---|---|
| When to call `flush()` (network callback, worker, UI) | Durable queue on disk |
| UX for `OfflineQueuedException` | Capture headers + body on connectivity failure |
| Idempotent server APIs (recommended) | Ordered replay via `flush()`, DLQ, crash recovery |
| Ktor `HttpClient` config (auth, JSON, etc.) | `getHeadState()` for UI inspection |

---

## Guarantees & honest limits

**Designed for:** offline / flaky mobile networks, one queue file per app, cooperative processes (UI + background worker), local or reliable storage.

| ✅ Strong | ⚠️ Know this |
|---|---|
| Survives app kill mid-queue | **At-least-once** delivery — plan for duplicates on critical mutations |
| Two processes won’t double-replay the same head entry | **You** must call `flush()` after reconnect — not automatic network listening |
| Corrupt tail records recovered via scan | Default **64 MiB** max per field — raise `maxRecordFieldSize` if needed |
| Slow uploads keep replay claims alive (renewal during send) | **4xx** → dead letter, not retry forever |
| | Advisory file locks — exotic network filesystems (some NFS setups) may differ |

Full change history: [CHANGELOG.md](CHANGELOG.md).

---

## Build & test

```bash
./gradlew ciTestJvm ciCompile    # Linux CI parity
./gradlew :ghost-sync:jvmTest   # library unit tests only
./gradlew ciCoverage            # Kover gate (≥90% JVM)
```

Try the demo:

```bash
./gradlew :sync-sample:composeApp:run
```

---

## Publishing

Sonatype + GPG in `~/.gradle/gradle.properties`, then:

```bash
./gradlew publishToMavenCentral
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
