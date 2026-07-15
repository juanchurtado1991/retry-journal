# Installation

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

> **iOS:** Kotlin/Native targets build on macOS.

Want background sync out of the box too, instead of wiring `WorkManager`/`BGTaskScheduler` yourself? See **[Background worker](background-worker.md)** for the optional `:retry-worker` artifact.

---

**Next:** [Quick start](quick-start.md) →

[← Back to docs](README.md)
