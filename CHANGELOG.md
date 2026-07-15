# Changelog

All notable changes to `ghost-sync` are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0] - 2026-07-14

First release.

### Added

- `GhostSync` + `GhostOfflineQueuePlugin` — durable offline HTTP mutation queue for Ktor
- `GhostSyncRuntime` — serialized `flush()`, connectivity-driven auto-flush, `flushWhenOnline()`, `shutdown()`
- `GhostSyncEngine.flush()` + `getHeadState()` — the only public replay surface
- `HeadReplayExecutor` — internal state machine for head replay (`AwaitingReplay`, `AwaitingLocalRemoval`, `Blocked`, `Empty`)
- Per-sequence delivery journals — `<queuePath>.delivery-pending.<sequenceId>` two-phase commit after HTTP/DLQ success
- `DeadLetterQueue` — persistent dead-letter storage with retry and cross-process serialization
- `ReplayClaim` — cross-process head claim so two processes cannot duplicate a non-idempotent POST
- `DiskQueue` — append-only on-disk queue with crash recovery, compaction, and advisory file locking
- `FrozenHttpHeaders` — flat header storage for faithful replay
- Apache 2.0 LICENSE, Maven Central publish wiring, GitHub Actions CI
- 180+ JVM unit tests, Kover coverage gate ≥90%

### Fixed

- Race conditions in `GhostSyncRuntimeTest` concurrent flush tests using `CompletableDeferred` instead of timing-dependent delays.
- Target-specific compilation errors on iOS targets (resolved `timeoutInterval` assignment in Darwin engine, POSIX `open` argument mapping, and `Dispatchers.IO` visibility).
- KSP code generation `@OptionalExpectation` errors on native/iOS targets via a `suppressGhostKspWarnings` post-processing Gradle task.

### Changed

- Refactored `build.gradle.kts` files to extract publishing, Kover, and warning configurations into separate files under `gradle/` (`publishing.gradle`, `kover.gradle`, and `warnings.gradle.kts`).
- Renamed `fd` to `fileDescriptor` in `PlatformQueueFileLock` and added `INVALID_FILE_DESCRIPTOR` constant.
- Created `KmpWorkManagerHelper` and `SampleIosWorkerFactory` to bridge iOS background tasks and DI initialization cleanly to Swift.
- Resolved and configured the iOS sample application build settings and static `Info.plist` to support Background Modes (fetch, processing) and registered `ghost_sync_task` and `kmp_chain_executor_task`.

[1.0.0]: https://github.com/juanchurtado1991/ghost-sync-kmp/releases/tag/v1.0.0

