# Background worker (`:retry-worker`)

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

`:retry-worker` is a separate Maven artifact that **implements** that contract so you don't have to: `androidx.work` (WorkManager) on Android, `BGTaskScheduler` on iOS, and a no-op on JVM/desktop (no OS-standardized background scheduler there — drive `flush()` from your own timer instead, see [Scheduling](scheduling.md)). Use it, or implement `RetryJournalScheduler` yourself against whatever scheduler your app already has — `:retry-journal` never knows the difference.

> **Weight:** on Android, `:retry-worker` pulls in WorkManager plus its internal Room/SQLite database — roughly 4 MB of raw AAR before shrinking (mostly `work-runtime` itself, ~2 MB). iOS and JVM add nothing (`BGTaskScheduler` is a system framework; the JVM scheduler is a no-op). If that's not worth it for your app, implementing `RetryJournalScheduler` yourself is just two functions.

## Installation

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

## Android

One call, once, after building your [`RetryJournalRuntime`](runtime.md) — it registers the periodic worker and schedules it via `WorkManager.getInstance(context).enqueueUniquePeriodicWork(...)`:

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

## iOS

Apple requires `BGTaskScheduler` registration to happen **synchronously**, before `application(_:didFinishLaunchingWithOptions:)` returns. Two things Apple requires you to set up by hand — no library can do this part for you:

1. Declare the task identifier in **Info.plist**.
2. Call the registration function from **`AppDelegate.init()`**, synchronously.

Everything else (submitting requests, re-submitting on every run) is handled for you:

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

`registerRetryJournalBackgroundTask` registers the `BGAppRefreshTask` launch handler, submits the first request, and re-submits the next one every time the task runs — `BGTaskScheduler` never repeats a request on its own, so you don't have to think about it once this is wired.

## `RetryJournalSchedulerConfig` fields

| Field | Default | Android | iOS |
|---|---|---|---|
| `intervalMs` | 15 min | Target period; WorkManager floors it to 15 min regardless. | `earliestBeginDate` offset for the next `BGAppRefreshTaskRequest` — a target, not a guarantee; iOS decides the actual run time. |
| `requiresNetwork` | `true` | `NetworkType.NOT_REQUIRED` if `false`. | Not used — `BGAppRefreshTask` has no network constraint to set. |
| `retryDelayMs` | 60 s | Backoff delay (`BackoffPolicy.LINEAR`) between retries; WorkManager floors it to 10 s. | Not used. |
| `maxRetryAttempts` | 5 | After this many attempts, the worker reports failure instead of retrying again. | Not used — every scheduled run is independent; there's no attempt cap. |

Full reference wiring for both platforms: [sample app](../retry-sample/README.md).

---

[← Back to docs](README.md)
