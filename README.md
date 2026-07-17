# 🔁 retry-journal

[![Maven Central](https://img.shields.io/maven-central/v/com.ghostserializer/retry-journal.svg?label=Maven%20Central)](https://search.maven.org/artifact/com.ghostserializer/retry-journal) [![CI](https://img.shields.io/github/actions/workflow/status/juanchurtado1991/retry-journal/ci.yml?branch=main&label=CI)](https://github.com/juanchurtado1991/retry-journal/actions/workflows/ci.yml) [![Platforms](https://img.shields.io/badge/platform-android%20%7C%20ios%20%7C%20jvm-blue)](docs/installation.md) [![Tests](https://img.shields.io/badge/tests-250%2B-brightgreen)](docs/development.md) [![License](https://img.shields.io/github/license/juanchurtado1991/retry-journal)](LICENSE) [![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/juanchurtado1991/retry-journal/pulls)

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
retry-journal = { module = "com.ghostserializer:retry-journal", version = "1.1.0" }
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

## Docs

| Topic | What's in it |
|---|---|
| **[Installation](docs/installation.md)** | Adding the dependency |
| **[Quick start](docs/quick-start.md)** | Create the queue, send requests, flush |
| **[Architecture & Reliability](docs/architecture.md)** | Append-only logs, memory index, locking, claims |
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
