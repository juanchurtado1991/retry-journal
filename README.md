# ghost-sync

**An offline-first HTTP sync engine for Kotlin Multiplatform.** Requests made while offline (or
during a flaky connection) are captured automatically, persisted crash-safely to disk, and
replayed the moment connectivity returns.

- **No database** — a single append-only file, purpose-built for this, not a table pressed into service
- **No scheduler dependency** — call `flush()` from a coroutine loop, `WorkManager`, a button, or nothing at all
- **Any serializer** — Ghost, kotlinx.serialization, or both, your choice
- **Files and images, not just JSON** — a multipart upload is captured exactly like any other request
- **Crash-safe** — kill the process mid-write and the queue recovers itself on next open, automatically

Built on the [Ghost](https://github.com/juanchurtado1991/ghost-serializer) serialization engine.
`:ghost-sync` is a single Gradle module — one artifact, `com.ghostserializer:ghost-sync` — covering
the disk queue, the Ktor client interceptor, and the sync engine. See [CONVENTIONS.md](CONVENTIONS.md)
for the coding rules and architecture decisions this repository holds itself to.

---

## The problem

Any app that lets a user act while offline eventually needs an **outbox**: somewhere to hold
requests that couldn't be sent yet, and a mechanism to drain that outbox once the network is back.
Get it wrong and you either lose user data (request silently fails) or duplicate it (naive retry
resends something that already landed).

The usual answer is *"put a table in the local database."* `ghost-sync` deliberately doesn't do
that:

| | A DB-backed outbox (Room / SQLDelight table) | `ghost-sync` |
|---|---|---|
| **Encodings per request** | Your object → DB row/JSON column → **re-encoded again** to send over the wire | Captured **once**, already serialized by your `HttpClient`'s own `ContentNegotiation` — never re-encoded |
| **Dependency weight** | An embedded SQL engine + ORM/codegen just to store "pending HTTP request" | Okio, already transitive through most KMP networking stacks |
| **Crash safety** | WAL + fsync + transactions — general-purpose durability built for arbitrary writes | One append-only file with a CRC32 per record — purpose-built for a single FIFO stream |
| **Schema** | A table (and migrations) to represent "method, url, headers, body" | No schema — the record *is* the request, in the shape it left the wire |

Databases are great at what they're for: querying, filtering, relating structured data. An HTTP
outbox doesn't need any of that — it needs to remember bytes in order and hand them back in order.
That narrower job is what lets `ghost-sync` stay small and fast.

## How it works

```
Your app
  │  HttpClient.post(...)
  ▼
GhostOfflineQueuePlugin ── online, 2xx ──▶  delivered
  │
  │  offline → IOException
  ▼
DiskQueue ── append-only file, CRC32 per record, survives a kill mid-write
  │
  │  GhostSyncEngine.flush() ── called by any scheduler, or none at all
  ▼
replay each queued entry against a plain HttpClient
```

| The replay gets back | What happens |
|---|---|
| `2xx` | Delivered — removed from the queue |
| `4xx` | A business rejection, not a connectivity one — moved to `DeadLetterQueue` |
| `5xx` or `IOException` | Still offline/down — stop here, the rest waits for the next `flush()` |

- **`GhostOfflineQueuePlugin`** installs on your `HttpClient`'s send pipeline (the same extension
  point Ktor's own `HttpRequestRetry` uses). On a connectivity failure it captures the exact bytes
  that were about to go out — a JSON body, a multipart file, anything your `ContentNegotiation`
  already produced — and appends them to `DiskQueue`, then rethrows as `OfflineQueuedException` so
  your UI can show "saved for later" instead of a generic error.
- **`DiskQueue`** never rewrites anything already on disk. `remove()` appends a *tombstone*
  referencing the entry by a persisted sequence id, instead of touching the original record. A
  process kill mid-write can only ever corrupt the last, incomplete record — the queue recovers by
  trimming it on next open, never by failing to start. Past 80% dead space it compacts by writing a
  fresh file and atomically replacing the old one; sequence ids survive compaction, so an id handed
  out before one stays valid after it.
- **`GhostSyncEngine.flush(client)`** is a plain `suspend fun`. It doesn't know or care what called
  it — see [Scheduling](#scheduling-any-of-them-or-none) below.
- **`DeadLetterQueue`** holds requests the server actively rejected. `peekAll()` / `retry()` /
  `discard()` are public, so you can build a "failed requests" screen instead of losing them
  silently.

## Install

```kotlin
// libs.versions.toml
[libraries]
ghost-sync = { module = "com.ghostserializer:ghost-sync", version = "0.1.0" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.ghost.sync)
}
```

Targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm` — the same set Ghost itself publishes.

## Quick start

The one type you need is `GhostSync`. It wires `DiskQueue` + `DeadLetterQueue` + `GhostSyncEngine`
and installs `GhostOfflineQueuePlugin` on a ready-to-use `HttpClient`:

```kotlin
val ghostSync = GhostSync.create(
    engineFactory = CIO,                       // or OkHttp, Darwin, any HttpClientEngineFactory
    queuePath = "/path/to/app/ghost-sync-queue.bin".toPath(),
) {
    // this block configures BOTH the live client and the internal replay client identically —
    // put your ContentNegotiation, auth, logging, timeouts, etc. here.
    install(ContentNegotiation) { ghost() }
}
```

Then just... make requests. `ghostSync.client` behaves like any other `HttpClient`:

```kotlin
try {
    ghostSync.client.post("https://api.example.com/mutations") {
        contentType(ContentType.Application.Json)
        setBody(myPayload)
    }
    // delivered immediately
} catch (e: OfflineQueuedException) {
    // no network right now — already safely on disk, will be retried
}
```

And drain the queue whenever you want (see [Scheduling](#scheduling-any-of-them-or-none) for how
to automate this):

```kotlin
val result = ghostSync.flush()
println("delivered=${result.delivered} deadLettered=${result.deadLettered} stoppedEarly=${result.stoppedEarly}")
```

Every piece `GhostSync` builds is public — `ghostSync.diskQueue`, `ghostSync.deadLetterQueue`,
`ghostSync.engine` — so nothing above is the only way to use this library; it's a convenience
default. Wire `DiskQueue`, `GhostOfflineQueuePlugin`, and `GhostSyncEngine` yourself if you need
something the facade doesn't expose (a custom `FileSystem` per queue, independent live/replay
client configuration, etc.).

### With Ghost (recommended — this is also what the queue uses internally)

```kotlin
install(ContentNegotiation) { ghost() }
```

```kotlin
@GhostSerialization
data class CreateOrder(val sku: String, val quantity: Int)

ghostSync.client.post(url) { setBody(CreateOrder("sku-123", 2)) }
```

### With kotlinx.serialization

`ghost-sync` never looks at *what* serialized your request body — only Ghost's own internal
bookkeeping record (method/url/headers, one small struct per queued entry) uses Ghost, and that's
invisible to you. Your payloads can use anything Ktor's `ContentNegotiation` supports:

```kotlin
install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
```

```kotlin
@Serializable
data class CreateOrder(val sku: String, val quantity: Int)

ghostSync.client.post(url) { setBody(CreateOrder("sku-123", 2)) }
```

This is not a hypothetical — [`GhostSyncSerializerAgnosticTest`](ghost-sync/src/jvmTest/kotlin/com/ghostserializer/sync/GhostSyncSerializerAgnosticTest.kt)
drives the full offline-queue-then-flush cycle through kotlinx.serialization's `json()` with zero
Ghost involvement in the payload path, end to end.

You can also install both converters together (Ghost's own coexistence pattern — `ghost()` handles
`@GhostSerialization` types, `json()` falls back for everything else) and mix them freely across
endpoints.

## Files and images

`GhostOfflineQueuePlugin` captures whatever bytes your `HttpClient` was actually about to send —
that includes a multipart file upload, not just a JSON body:

```kotlin
ghostSync.client.post(url) {
    setBody(
        MultiPartFormDataContent(
            formData {
                append(
                    "photo", photoBytes,
                    Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"photo.jpg\"") },
                )
            },
        ),
    )
}
```

Offline, the whole file is queued exactly like any other request — no path reference, no
dependency on the original file still being there whenever connectivity comes back.

A single queued body is capped at **64 MiB** by default — past that, `enqueue()` throws
`RecordTooLargeException` instead of writing something it could never read back. Raise it if your
uploads are routinely bigger:

```kotlin
GhostSync.create(engineFactory, queuePath, maxRecordFieldSize = 200 * 1024 * 1024) { ... }
```

Queueing a file is a memory/disk tradeoff, not free: the whole body is read into memory once to
capture it, and a full copy lives on disk until it's delivered. `ghost-sync` is built for
**retrying a request**, not for being a general file-transfer manager — for very large or
long-lived uploads, have your app keep its own stable copy of the file and queue a small reference
to it instead of the file itself.

## Watching sync happen

`flush()` normally just returns one `FlushResult` once the whole queue is drained. Pass
`onProgress` to react as each entry actually resolves — a UI can show requests draining out of the
queue in real time instead of only a summary at the end:

```kotlin
ghostSync.flush { progress ->
    when (progress) {
        is FlushProgress.Delivered -> showDelivered(progress.id)
        is FlushProgress.DeadLettered -> showRejected(progress.id)
    }
}
```

For full manual control — peek one entry, decide what to do with it, repeat — `GhostSyncEngine`
also exposes `getEntry()` and `getStatus(client, entry)` as building blocks; `flush()` itself is
built on nothing more than those two.

## Scheduling: any of them, or none

`GhostSyncEngine.flush()` — and therefore `GhostSync.flush()` — takes no scheduler dependency.
`:ghost-sync` does not import `kmpworkmanager`, `androidx.work`, or anything like them; whatever
triggers a flush is entirely your call:

```kotlin
// Simplest possible driver — a coroutine loop, works everywhere including JVM/desktop:
while (isActive) {
    delay(15.minutes)
    ghostSync.flush()
}
```

```kotlin
// A real background-dispatch integration, using dev.brewkits:kmpworkmanager as the reference
// (see sync-sample/composeApp for the full, verified wiring — Worker/WorkerResult/scheduler
// package names were confirmed against the actual compiled artifact, not just its README):
class SyncWorker(private val ghostSync: GhostSync) : Worker {
    override suspend fun doWork(input: String?, env: WorkerEnvironment): WorkerResult {
        val result = ghostSync.flush()
        return if (result.stoppedEarly) {
            WorkerResult.Retry(reason = "work remains", delayMs = 60_000L, attemptCap = 5)
        } else {
            WorkerResult.Success("delivered=${result.delivered}")
        }
    }
}
```

`androidx.work.CoroutineWorker`, a plain `Timer`, a server-side cron, or a button in your UI all
work the same way — `flush()` doesn't know or care which one called it.

## Extensibility via `expect`/`actual`

`sync-sample/composeApp` is the reference for wiring `ghost-sync` into a real multiplatform app,
and it deliberately isolates every platform-specific decision behind a small `expect`/`actual`
seam, so you can swap any of them for your own app without touching shared code:

| Seam | `expect` declaration | Android `actual` | iOS `actual` | Swap it for |
|---|---|---|---|---|
| HTTP engine | `platformHttpClientEngine(): HttpClientEngine` | `OkHttp` with a real socket timeout | `Darwin` with a real socket timeout | `CIO`, a mocked engine for tests, a custom-configured `OkHttp`/`Darwin` |
| Queue storage location | `platformDataDirectory(): String` | `Application.filesDir` | `NSDocumentDirectory` | Any app-private writable directory — a different `NSSearchPathDirectory`, a scoped-storage path, etc. |

Both live in [`sync-sample/composeApp/src/commonMain/.../PlatformHttpClientEngine.kt`](sync-sample/composeApp/src/commonMain/kotlin/com/ghostserializer/sync/sample/app/PlatformHttpClientEngine.kt)
and [`PlatformDataDirectory.kt`](sync-sample/composeApp/src/commonMain/kotlin/com/ghostserializer/sync/sample/app/PlatformDataDirectory.kt) —
copy the pattern, not the specific engine choice, into your own app. The scheduler integration
(`kmpworkmanager` in the sample) is the same story at a coarser grain: it's not an `expect`/`actual`
pair inside `ghost-sync` because it isn't part of the library at all — swapping it means writing a
different `Worker`/`CoroutineWorker`/timer that calls `ghostSync.flush()`, nothing more.

## Dead letters

```kotlin
ghostSync.deadLetterQueue.peekAll()          // build a "failed requests" screen
ghostSync.deadLetterQueue.retry(entry.id)    // re-enqueues on the main queue for the next flush()
ghostSync.deadLetterQueue.discard(entry.id)  // gone for good
```

## Build & test

```bash
./gradlew :ghost-sync:jvmTest        # 30 unit tests, JVM only, no emulator needed
./gradlew :sync-sample:server:run    # chaotic Ktor server for manual/full-cycle testing
```

Want to see the whole thing running with a UI in under a minute, no emulator or device required?
`./gradlew :sync-sample:composeApp:run` starts a desktop build with the chaos server embedded in
the same process — see [sync-sample/README.md](sync-sample/README.md).

iOS targets are automatically skipped by Gradle on non-macOS hosts
(`kotlin.native.ignoreDisabledTargets=true`), same as `ghost-serializer`'s own CI.

## Modules

| Module | What it is |
|---|---|
| `:ghost-sync` | The library. `GhostSync` (facade) at the root; `queue/` (`DiskQueue`), `deadletter/` (`DeadLetterQueue`), `client/` (`GhostOfflineQueuePlugin`), `engine/` (`GhostSyncEngine`) below it |
| `sync-sample/shared`, `sync-sample/server`, `sync-sample/composeApp`, `sync-sample/iosApp` | Full-cycle demo app — **never shipped with the library**, kept in its own top-level module with no publish configuration at all — see [sync-sample/README.md](sync-sample/README.md) |
