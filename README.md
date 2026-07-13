# ghost-sync

Offline-first sync engine for Kotlin Multiplatform, built on the [Ghost](https://github.com/juanchurtado1991/ghost-serializer)
serialization engine. `:ghost-sync` is the only published module — a single Gradle artifact
(`com.ghostserializer:ghost-sync`) covering the append-only disk queue, the Ktor client
interceptor, and the dependency-free sync engine. See [CONVENTIONS.md](CONVENTIONS.md) for the
project's coding rules and the architecture decisions that are considered settled.

## Modules

| Module | What it is |
|---|---|
| `:ghost-sync` | The library. `queue/` (`DiskQueue`), `deadletter/` (`DeadLetterQueue`), `client/` (`GhostOfflineQueuePlugin`), `engine/` (`GhostSyncEngine`) |
| `sample/shared`, `sample/server`, `sample/composeApp`, `sample/iosApp` | Full-cycle demo app — see [sample/README.md](sample/README.md) |

## How it works, in one paragraph

A Ktor client plugin (`GhostOfflineQueuePlugin`) catches connectivity failures and appends the
already-Ghost-serialized request to `DiskQueue`, an append-only file: nothing is ever rewritten in
place, so an abrupt process kill mid-write can only ever corrupt the last, incomplete record — the
queue recovers by truncating it, not by failing to open. `GhostSyncEngine.flush()` reads the queue
back against any `HttpClient`, removing entries that succeed, moving business failures (4xx) to a
`DeadLetterQueue` with its own public `retry`/`discard` API, and stopping the loop untouched on a
5xx or network error for the next attempt. `flush()` takes no scheduler dependency at all —
`kmpworkmanager`, `androidx.work`, or a plain coroutine timer can all drive it; see `sample/composeApp`
for a real `kmpworkmanager` integration.

## Build & test

```bash
./gradlew :ghost-sync:jvmTest   # 18 unit tests, JVM only, no emulator needed
./gradlew :sample:server:run    # chaotic Ktor server for manual/full-cycle testing
```

`:ghost-sync` targets `android`, `iosArm64`, `iosSimulatorArm64`, `jvm` — the same set Ghost
itself publishes. iOS targets are automatically skipped by Gradle on non-macOS hosts
(`kotlin.native.ignoreDisabledTargets=true`), same as `ghost-serializer`'s own CI.
