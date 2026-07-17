# 🔁 retry-journal

**Never lose an HTTP request because the network dropped — offline-first HTTP request replay for Kotlin Multiplatform.**

Every pending request costs exactly one `Long` (8 bytes) in memory, with O(1) lookup no matter how deep the backlog gets — real numbers in the [benchmarks](docs/development.md#performance-report).

`retry-journal` is a lightweight, durable **HTTP Request Replay Queue** for Android, iOS, and JVM. It intercepts outgoing Ktor requests on network failures, saves them to disk, and replays them exactly as sent when connectivity returns.

> [!NOTE]
> This is a dedicated **HTTP outbox queue**, not a general-purpose database synchronizer (like Room/SQLDelight state sync). It focuses purely on guaranteeing HTTP delivery.

![retry-journal running on Android, iOS, and Desktop — offline queue and sync in action](docs/demo.gif)

| Without retry-journal | With retry-journal |
|---|---|
| User offline → request fails, data lost | Request **queued on disk**, clear "saved for later" UX |
| App killed mid-request → maybe nothing persisted | **Crash-safe** append-only file + CRC recovery |
| Background worker + UI both sync → duplicate POSTs | **Cross-process locks** + replay claims |
| Server returns 400 → infinite retry loop | **Dead letter queue** — inspect, retry, or discard |

---

## Install

```kotlin
// libs.versions.toml
retry-journal = { module = "com.ghostserializer:retry-journal", version = "1.0.0" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.retry.journal)
}
```

**Targets:** `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`. Details: [docs/installation.md](docs/installation.md).

## 30-second example

```kotlin
val retryJournal = RetryJournal.create(engineFactory = CIO, queuePath = dataDir.resolve("queue.bin"))

try {
    retryJournal.client.post("https://api.example.com/orders") { setBody(order) }
} catch (_: OfflineQueuedException) {
    showMessage("Saved — will sync when you're back online.")
}

// later, when you decide there's connectivity:
val result = retryJournal.flush()
```

Full walkthrough: [docs/quick-start.md](docs/quick-start.md).
---

## Architecture & Reliability

To prevent data loss and ensure robust performance, `retry-journal` uses several advanced architectural techniques:

### 1. Crash-Safe Storage & Byte-by-Byte Recovery
* **Append-Only Log**: Outbox records are appended to a flat binary file. Modifying a record (like marking it as removed/tombstoned) simply appends a lightweight tombstone marker, keeping disk writes fast and avoiding expensive in-place mutations.
* **CRC32 Verification**: Every record is written with a CRC32 checksum wrapping its headers, URL, and body. 
* **Safe Resync Scan**: If the app crashes mid-write, `retry-journal` runs a recovery scan on boot. If it hits a CRC mismatch, it drops back to a safe byte-by-byte scan rather than trusting corrupted header metadata to skip ahead, discarding only the corrupted trailing bytes.

### 2. Multi-Process Concurrency & Path Aliasing Protection
* **Platform Queue File Locks**: Multi-platform file locking (`PlatformQueueFileLock`) ensures only one process (e.g. your app UI and a background OS sync worker) can write to or flush the queue at a time.
* **Canonical Path Mapping**: To prevent `OverlappingFileLockException` on JVM and Android, the file lock resolves real/canonical paths. Using a symlink or directory alias points to the same underlying lock key, preventing concurrent processes from acquiring overlapping locks.

### 3. Highly-Optimized Memory Indexing
* **`LiveEntryIndex` (Dense Long Array)**: Keeping hundreds of thousands of entries in memory using typical Kotlin/Java collections like `LinkedHashMap` leads to excessive garbage collection and high memory overhead.
* **Zero-Allocation Mapping**: We map sequence IDs to packed disk offsets and record lengths inside a single primitive `LongArray`. The sequence ID is implicit in the array index, using bit-packing to store the offset and length in one 64-bit value. This reduces memory footprint by ~90% (8 bytes per entry).
* **Span Safety**: We guard the index against runaway sequence ID gaps (from long-term stuck head entries) with a `MAX_SPAN` threshold, failing cleanly instead of risking an `OutOfMemoryError`.

### 4. Zero-Leak Delivery & Retry Journals
* **Head Replay Claims**: To ensure at-most-once delivery during slow or hanging connections, the engine locks the queue head using a temporary `.claim` file on disk. Claims are refreshed concurrently during active uploads and automatically expire or safely handle wall-clock jumps.
* **Outcome Journals**: Delivery and Dead-letter transactions write their state to a `.journal` file before modifying the queue itself. If the process is killed mid-write, the engine detects the journal on reboot, ensuring that completed requests are never re-sent or lost.

---

## Docs

| Topic | What's in it |
|---|---|
| **[Installation](docs/installation.md)** | Adding the dependency |
| **[Quick start](docs/quick-start.md)** | Create the queue, send requests, flush |
| **[Scheduling `flush()`](docs/scheduling.md)** | DIY patterns, or the out-of-the-box worker |
| **[Background worker (`:retry-worker`)](docs/background-worker.md)** | WorkManager / BGTaskScheduler, zero glue code |
| **[RetryJournalRuntime](docs/runtime.md)** | Serialize `flush()` across multiple callers |
| **[File & image uploads](docs/uploads.md)** | Multipart uploads captured and replayed offline |
| **[Dead letters](docs/dead-letters.md)** | What happens when the server says no |
| **[Guarantees & honest limits](docs/guarantees.md)** | What's strong, what to watch out for |
| **[Build, test & publish](docs/development.md)** | Contributing to retry-journal itself |

Full index: [docs/README.md](docs/README.md).

See it in action: [sample app](retry-sample/README.md) — a working Compose Multiplatform demo (desktop/Android/iOS).

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
