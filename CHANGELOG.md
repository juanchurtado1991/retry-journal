# Changelog

All notable changes to `retry-journal` are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- `:retry-worker` — new Maven module with an out-of-the-box background scheduler: `androidx.work` (WorkManager) on Android, `BGTaskScheduler` on iOS, a no-op on JVM/desktop.
- `RetryJournalScheduler` / `RetryJournalSchedulerConfig` (`:retry-journal`, package `scheduler/`) — the scheduler-agnostic contract `:retry-worker` implements; any app can implement it directly instead.

### Removed

- The `kmpworkmanager` dependency, dropped entirely from the project and from `retry-sample`, in favor of `:retry-worker`.

### Changed

- `retry-sample/composeApp` now wires background sync through `:retry-worker`'s `setupBackgroundSync()` (Android) and `registerRetryJournalBackgroundTask()` (iOS) instead of `kmpworkmanager`'s `@Worker`/Koin setup.

## [1.0.0] - 2026-07-14

First release.

### Added

- `RetryJournal` + `RetryJournalOfflineQueuePlugin` — durable offline HTTP mutation queue for Ktor
- `RetryJournalRuntime` — serialized `flush()`, connectivity-driven auto-flush, `flushWhenOnline()`, `shutdown()`
- `RetryJournalEngine.flush()` + `getHeadState()` — the only public replay surface
- `HeadReplayExecutor` — internal state machine for head replay (`AwaitingReplay`, `AwaitingLocalRemoval`, `Blocked`, `Empty`)
- Per-sequence delivery journals — `<queuePath>.delivery-pending.<sequenceId>` two-phase commit after HTTP/DLQ success
- `DeadLetterQueue` — persistent dead-letter storage with retry and cross-process serialization
- `ReplayClaim` — cross-process head claim so two processes cannot duplicate a non-idempotent POST
- `DiskQueue` — append-only on-disk queue with crash recovery, compaction, and advisory file locking
- `FrozenHttpHeaders` — flat header storage for faithful replay
- Apache 2.0 LICENSE, Maven Central publish wiring, GitHub Actions CI
- 180+ JVM unit tests, Kover coverage gate ≥90%

### Fixed

- Race conditions in `RetryJournalRuntimeTest` concurrent flush tests using `CompletableDeferred` instead of timing-dependent delays.
- Target-specific compilation errors on iOS targets (resolved `timeoutInterval` assignment in Darwin engine, POSIX `open` argument mapping, and `Dispatchers.IO` visibility).
- KSP code generation `@OptionalExpectation` errors on native/iOS targets via a `suppressGhostKspWarnings` post-processing Gradle task.

### Changed

- Refactored `build.gradle.kts` files to extract publishing, Kover, and warning configurations into separate files under `gradle/` (`publishing.gradle`, `kover.gradle`, and `warnings.gradle.kts`).
- Renamed `fd` to `fileDescriptor` in `PlatformQueueFileLock` and added `INVALID_FILE_DESCRIPTOR` constant.
- Created `KmpWorkManagerHelper` and `SampleIosWorkerFactory` to bridge iOS background tasks and DI initialization cleanly to Swift.
- Resolved and configured the iOS sample application build settings and static `Info.plist` to support Background Modes (fetch, processing) and registered `retry_journal_task` and `kmp_chain_executor_task`.

[1.0.0]: https://github.com/juanchurtado1991/retry-journal/releases/tag/v1.0.0

