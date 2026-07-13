# 👻 ghost-sync

**Offline-first HTTP sync engine for Kotlin Multiplatform.** 

`ghost-sync` automatically captures HTTP requests made while offline (or during connectivity drops), saves them safely to disk, and replays them automatically once connection is restored.

---

## ✨ Features

- 📦 **No Database Needed**: Uses a single, fast, append-only file instead of heavy SQL tables.
- ⚡ **No Scheduler Lock-in**: Run `flush()` anywhere (coroutine loops, WorkManager, button clicks).
- 🔄 **Compatible with Any Serializer**: Works with Ghost, kotlinx.serialization, or custom JSON.
- 📁 **Supports Files & Images**: Captures full multipart payloads exactly as they left the client.
- 🛡️ **Crash-Safe**: CRC32 checks on every record automatically repair any partial write crashes.
- 📬 **Dead Letter Queue (DLQ)**: Easily inspect and retry requests rejected by the server.

---

## 🚀 How it Works

```
Your App ────────▶ HttpClient.post(...)
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
          [Online]           [Offline]
             │                   │
             ▼                   ▼
         Delivered           DiskQueue (Saved to disk)
                                 │
                                 ▼ (GhostSyncEngine.flush())
                             Replay Request
```

### Response Replay Outcomes:
- **`2xx` (Success)**: Request delivered and removed from the queue.
- **`4xx` (Client Error)**: Server rejected the request; it is moved to the **DeadLetterQueue** so you can inspect/fix it.
- **`5xx` or `IOException` (Server/Network Error)**: Senders wait and try again on the next `flush()`.

---

## 📦 Installation

Add `ghost-sync` to your Kotlin Multiplatform project:

```kotlin
// libs.versions.toml
[libraries]
ghost-sync = { module = "com.ghostserializer:ghost-sync", version = "0.1.0" }
```

```kotlin
// build.gradle.kts (shared module)
dependencies {
    implementation(libs.ghost.sync)
}
```

*Supported targets: `android`, `iosArm64`, `iosSimulatorArm64`, and `jvm`.*

> **iOS note:** iOS targets compile on macOS only. Verification steps are tracked in [`ios_techdebt.md`](ios_techdebt.md).

### Important constraints
- **Cross-process safe:** multiple processes can share the same queue file — `DiskQueue` acquires an advisory lock at `<queuePath>.lock` on every operation.
- **Version `0.1.0`:** pre-release — see [CHANGELOG.md](CHANGELOG.md) for known limitations.

---

## ⚡ Quick Start

### 1. Initialize GhostSync
Create a `GhostSync` instance. This automatically wires the `DiskQueue`, `DeadLetterQueue`, and the sync client:

```kotlin
val ghostSync = GhostSync.create(
    engineFactory = CIO, // CIO, OkHttp, Darwin, etc.
    queuePath = "/path/to/app/ghost-sync-queue.bin".toPath(),
) {
    // Configure your ContentNegotiation, auth headers, logging, etc.
    install(ContentNegotiation) { ghost() }
}
```

### 2. Make Network Requests
Use the `ghostSync.client` just like a regular Ktor `HttpClient`:

```kotlin
try {
    ghostSync.client.post("https://api.example.com/orders") {
        contentType(ContentType.Application.Json)
        setBody(CreateOrder("sku-123", 2))
    }
} catch (e: OfflineQueuedException) {
    // We are offline! Request is safely queued on disk and will retry later.
}
```

### 3. Sync Pending Requests
Trigger queue replay whenever connectivity is restored:

```kotlin
val result = ghostSync.flush()
println("Delivered: ${result.delivered}, Failed: ${result.deadLettered}")
```

---

## 📬 Handling Failed Requests (Dead Letters)

When the server actively rejects a request (e.g. returns a `400 Bad Request`), it is sent to the `DeadLetterQueue` instead of being retried forever. You can easily build a "Failed Syncs" UI:

```kotlin
val failedRequests = mutableListOf<DeadLetterEntry>()
ghostSync.deadLetterQueue.peekAll(failedRequests)

// Retry a specific request
ghostSync.deadLetterQueue.retry(failedRequests.first().id)

// Discard it forever
ghostSync.deadLetterQueue.discard(failedRequests.first().id)
```

---

## 📂 Capturing Files & Multipart Data

`ghost-sync` automatically captures raw request bytes. This means you can queue image uploads offline without losing them:

```kotlin
ghostSync.client.post("https://api.example.com/upload") {
    setBody(
        MultiPartFormDataContent(
            formData {
                append("image", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"profile.jpg\"")
                })
            }
        )
    )
}
```

---

## ⏱️ Scheduling Flushes

You can trigger `flush()` using any scheduler or logic:

```kotlin
// Option A: Simple background loop
while (isActive) {
    delay(15.minutes)
    ghostSync.flush()
}

// Option B: Integration with WorkManager
class SyncWorker(private val ghostSync: GhostSync) : CoroutineWorker(...) {
    override suspend fun doWork(): Result {
        val result = ghostSync.flush()
        return if (result.stoppedEarly) Result.retry() else Result.success()
    }
}
```

---

## 🛠️ Build & Test

Run the full Linux-verifiable CI suite locally:
```bash
./gradlew ciTestJvm ciCompile
```

JVM unit tests only:
```bash
./gradlew :ghost-sync:jvmTest
```

### Publishing to Maven Central

Requires Sonatype credentials and GPG signing in `~/.gradle/gradle.properties`:
```properties
sonatypeUsername=...
sonatypePassword=...
signing.keyId=...
signing.password=...
signing.secretKeyRingFile=...
```

```bash
./gradlew publishToMavenCentral
```
