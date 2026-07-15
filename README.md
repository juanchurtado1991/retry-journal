# 🔁 retry-journal

**Never lose an HTTP request because the network dropped — Offline-first HTTP Request Replay for Kotlin Multiplatform.**

`retry-journal` is a lightweight, durable **HTTP Request Replay Queue** for Android, iOS, and JVM. It intercepts and captures outgoing Ktor requests on network failures, saves them to disk, and replays them exactly as originally configured when connectivity returns.

> [!NOTE]
> This is a dedicated **HTTP outbox queue**, not a general-purpose database synchronizer (like Room/SQLDelight state sync). It focuses purely on guaranteeing HTTP delivery.

---

## The problem you already know

| Without retry-journal | With retry-journal |
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
2. They post an order or upload a photo with retryJournal.client
      ↓
3. Network throws → plugin saves method, URL, headers, body bytes to disk
      ↓
4. App catches OfflineQueuedException → show “Saved — will sync when online”
      ↓
5. User gets Wi‑Fi again
      ↓
6. YOUR APP calls retryJournal.flush()  ← you wire this (WorkManager, NetworkCallback, etc.)
      ↓
7. Requests replay in order → 2xx removes from queue, done ✓
```

**Important:** retry-journal **persists** offline work and **replays** it when you call `flush()`. It does **not** watch the network by itself — you choose *when* to sync (background job on connectivity, periodic worker, “Sync now” button). That keeps the library small and lets *you* control battery and UX.

See the [sample app](retry-sample/README.md) for a working demo (toggle server off → upload → toggle on → sync).

---

## What happens on replay?

| Server / network result | What retry-journal does |
|---|---|
| **2xx Success** | Delivered — removed from the queue |
| **4xx Client error** (e.g. 400) | Moved to **Dead Letter Queue** — fix data or discard; no infinite retry |
| **5xx or network error again** | Stays on queue — try again on the next `flush()` |

Replay is **at-least-once**: if the server already accepted a 2xx but the app crashed before the entry was removed, the next `flush()` may send it again. Use **idempotent APIs** or server-side dedup keys for payments and other sensitive mutations.

---

## Features

- **Dedicated append-only queue file** — purpose-built FIFO HTTP sync pipeline (not a general-purpose DB; Room KMP is great for app state, retry-journal owns the offline mutation queue)
- **Any serializer** — your JSON layer is separate; the queue stores raw wire bytes
- **Files & multipart** — image uploads captured offline, replayed byte-for-byte
- **Crash-safe** — CRC32 on every record; recovery after partial writes
- **Dead Letter Queue** — UI-friendly list of server-rejected requests
- **Cross-process safe** — app + background worker can share one queue file
- **Scheduler-agnostic** — call `flush()` from WorkManager, coroutines, or a button

---

## Not Tape, not Room — an offline HTTP sync stack

Libraries like [Square Tape](https://github.com/square/tape) solve a **different** problem: a generic `byte[]` FIFO on disk (`QueueFile` / `ObjectQueue`). You still build capture, retry policy, HTTP replay, dead letters, and multi-process coordination yourself.

**retry-journal is the sync layer**, not a queue primitive:

| Tape-style file queue | retry-journal |
|---|---|
| Arbitrary `byte[]` blobs | Frozen HTTP requests — method, URL, headers, body bytes |
| Android / Java | **KMP** — Android, iOS, JVM |
| You wire networking & retry policy | **Ktor plugin** captures on `IOException` + **replay engine** applies 2xx / 4xx DLQ / 5xx retry |
| In-place header rewrites (Tape's own docs warn about corruption on conventional filesystems) | **Append-only** records + atomic rename — no in-place header mutation |
| Single-process assumption | **Cross-process** file locks + replay claims (app + WorkManager) |
| No delivery semantics | **Per-sequence delivery journal** — two-phase commit so a crash after server 2xx doesn't force a duplicate POST |

Room KMP is great for **app state** (users, settings, cached reads). retry-journal owns the **offline mutation queue** — the ordered pipe of POSTs/PUTs that must survive no signal and replay when you're back.

Tape is a fine brick if you want to build all of the above from scratch. retry-journal gives you the stack; you only decide **when** to call `flush()`.

---

## Installation

```kotlin
// libs.versions.toml
[libraries]
retry-journal = { module = "com.ghostserializer:retry-journal", version = "1.0.0" }
```

```kotlin
// build.gradle.kts (shared module)
dependencies {
    implementation(libs.retry.journal)
}
```

**Targets:** `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`.

Optional out-of-the-box background scheduling — `com.ghostserializer:retry-worker` (WorkManager on Android, `BGTaskScheduler` on iOS, no-op on JVM). See [Background scheduling out of the box](#background-scheduling-out-of-the-box-retry-worker).

> **iOS:** Kotlin/Native targets build on macOS. Handoff checklist: [`ios_techdebt.md`](ios_techdebt.md) (1.0.0 — compilar, sample E2E, publicar).

---

## Quick start (3 steps)

### 1. Create `RetryJournal`

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

### 2. Send requests with `retryJournal.client`

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

### 3. Sync when the network is back — `flush()`

Call this when **you** decide there is connectivity (see [Scheduling](#scheduling-flush-when-wifi-returns) below):

```kotlin
val result = retryJournal.flush()
// result.delivered      — sent successfully this run
// result.deadLettered   — server rejected (now in DLQ)
// result.stoppedEarly   — still offline or 5xx; run flush again later
// result.persistenceFailed — server accepted the request but local removal failed; journal left for recovery
```

### Inspect the queue head (UI)

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

## Scheduling flush when Wi‑Fi returns

This is the piece every production app needs. Examples:

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

// C) Manual “Sync now” in settings or after ConnectivityManager callback
syncButton.setOnClickListener {
    lifecycleScope.launch {
        val r = retryJournal.flush()
        showSnackbar("Synced ${r.delivered} requests")
    }
}
```

Want it out of the box instead of writing (A)/(C) yourself? Keep reading.

---

## Background scheduling out of the box (`:retry-worker`)

`:retry-journal` itself depends on **no scheduler** — it only defines the contract, in package `com.retryjournal.scheduler`:

```kotlin
interface RetryJournalScheduler {
    fun schedule(config: RetryJournalSchedulerConfig)
    fun cancel()
}

data class RetryJournalSchedulerConfig(
    val intervalMs: Long = 15 * 60 * 1_000L,  // WorkManager floors periodic work to 15 min anyway
    val requiresNetwork: Boolean = true,
    val retryDelayMs: Long = 60_000L,
    val maxRetryAttempts: Int = 5,
)
```

`:retry-worker` is a separate Maven artifact that **implements** that contract so you don't have to: `androidx.work` (WorkManager) on Android, `BGTaskScheduler` on iOS, and a no-op on JVM/desktop (no OS-standardized background scheduler there — drive `flush()` from your own timer instead, see (B) above). Use it, or implement `RetryJournalScheduler` yourself against whatever scheduler your app already has — `:retry-journal` never knows the difference.

### Installation

```kotlin
// libs.versions.toml
[libraries]
retry-worker = { module = "com.ghostserializer:retry-worker", version = "1.0.0" }
```

```kotlin
// build.gradle.kts (shared module)
dependencies {
    implementation(libs.retry.worker) // pulls in :retry-journal transitively
}
```

**Targets:** `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`.

### Android

One call, once, after building your `RetryJournalRuntime` (see [RetryJournalRuntime](#retryjournalruntime-optional-coordinator) below) — it registers the periodic worker and schedules it via `WorkManager.getInstance(context).enqueueUniquePeriodicWork(...)`:

```kotlin
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import com.retryjournal.worker.setupBackgroundSync

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runtime.setupBackgroundSync(
            context = this,
            config = RetryJournalSchedulerConfig(
                intervalMs = 15 * 60 * 1_000L,
                retryDelayMs = 60_000L,
                maxRetryAttempts = 5,
            ),
        )
    }
}
```

`setupBackgroundSync` returns the `RetryJournalScheduler` it created — hold onto it if you need `cancel()` later (e.g. on logout).

### iOS

Apple requires `BGTaskScheduler` registration to happen **synchronously**, before `application(_:didFinishLaunchingWithOptions:)` returns — so call the Kotlin function as early as possible, typically from `AppDelegate.init()`:

```kotlin
// Kotlin (iosMain) — a small facade keeps the exported Objective-C header trivial for Swift.
import com.retryjournal.scheduler.RetryJournalSchedulerConfig
import com.retryjournal.worker.registerRetryJournalBackgroundTask

object BackgroundSyncSetup {
    fun register() {
        registerRetryJournalBackgroundTask(
            taskIdentifier = "com.example.app.retry_journal_task",
            runtime = runtime,
            config = RetryJournalSchedulerConfig(),
        )
    }
}
```

```swift
// Swift — AppDelegate.swift
class AppDelegate: UIResponder, UIApplicationDelegate {
    override init() {
        super.init()
        BackgroundSyncSetup.shared.register()
    }
}
```

```xml
<!-- Info.plist -->
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.example.app.retry_journal_task</string>
</array>
```

`registerRetryJournalBackgroundTask` registers the `BGAppRefreshTask` launch handler, submits the first request, and re-submits the next one every time the task runs (`BGTaskScheduler` never repeats a request on its own).

### `RetryJournalSchedulerConfig` fields

| Field | Default | Android | iOS |
|---|---|---|---|
| `intervalMs` | 15 min | Target period; WorkManager floors it to 15 min regardless. | `earliestBeginDate` offset for the next `BGAppRefreshTaskRequest` — a target, not a guarantee; iOS decides the actual run time. |
| `requiresNetwork` | `true` | `NetworkType.NOT_REQUIRED` if `false`. | Not used — `BGAppRefreshTask` has no network constraint to set. |
| `retryDelayMs` | 60 s | Backoff delay (`BackoffPolicy.LINEAR`) between retries; WorkManager floors it to 10 s. | Not used. |
| `maxRetryAttempts` | 5 | After this many attempts, the worker reports failure instead of retrying again. | Not used — every scheduled run is independent; there's no attempt cap. |

Full reference wiring for both platforms: [retry-sample](retry-sample/README.md).

---

## RetryJournalRuntime (optional coordinator)

If multiple callers can trigger sync (UI button, `WorkManager`, connectivity callback), use
`RetryJournalRuntime` to **serialize `flush()`** and optionally **auto-flush when your app reports
online**:

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

If you wire [RetryJournalEngine] and a replay [HttpClient] yourself (e.g. separate live vs replay
clients in the sample), use `RetryJournalRuntime.createForEngine(engine, replayClient, scope, connectivity)`.

**The library does not observe the network or schedule background work** — you supply the
`Flow<Boolean>`. WorkManager / BGTask stay in your app (see [retry-sample](retry-sample/README.md)).

---

## File & image uploads offline

The plugin captures **whatever bytes** Ktor would have sent — including `MultiPartFormDataContent`:

```kotlin
retryJournal.client.post("https://api.example.com/upload") {
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

**Size limit:** each queued meta/body field defaults to **64 MiB** (`maxRecordFieldSize` in `RetryJournal.create`). Larger uploads fail closed with `BodyCaptureException` instead of silently truncating.

---

## Dead letters (server said no)

When replay gets a **non-retry-worthy 4xx**, the request moves to the dead letter queue instead of blocking everything behind it:

```kotlin
val failed = mutableListOf<DeadLetterEntry>()
retryJournal.deadLetterQueue.peekAll(failed)

// Let the user fix and retry
retryJournal.deadLetterQueue.retry(failed.first().id)

// Or discard permanently
retryJournal.deadLetterQueue.discard(failed.first().id)
```

Build a “Failed uploads” or “Sync issues” screen from `peekAll()`.

---

## What you build vs what the library gives you

| You provide | retry-journal provides |
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
./gradlew :retry-journal:jvmTest   # library unit tests only
./gradlew ciCoverage            # Kover gate (≥90% JVM)
```

Try the demo:

```bash
./gradlew :retry-sample:composeApp:run
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
