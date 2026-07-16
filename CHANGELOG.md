# Changelog

All notable changes to `retry-journal` are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

Nothing has been published yet — everything below is still pre-1.0.0 work in progress.

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
- `RetryJournalScheduler` / `RetryJournalSchedulerConfig` (`:retry-journal`, package `scheduler/`) — the scheduler-agnostic contract that `:retry-worker` implements; any app can implement it directly instead.
- `:retry-worker` — new Maven module with an out-of-the-box background scheduler: `androidx.work` (WorkManager) on Android, `BGTaskScheduler` on iOS, a no-op on JVM/desktop. On both platforms, a failed/incomplete run reschedules sooner (`retryDelayMs`) for up to `maxRetryAttempts` in a row before falling back to the normal interval.
- `retry-sample:server` normal mode (default — every request succeeds) alongside the existing chaos mode (`--args="chaos"`), so a large batch can replay in one `flush()` without an artificial failure interrupting it partway through.
- Apache 2.0 LICENSE, Maven Central publish wiring, GitHub Actions CI
- 190+ JVM unit tests (across `:retry-journal` and `:retry-worker`), Kover coverage gate ≥90%, plus real iOS-simulator tests for `:retry-worker`'s schedulers.

### Changed

- **Renamed the whole project from GhostSync to RetryJournal.** Modules `:ghost-sync` / `:ghost-sync-worker` are now `:retry-journal` / `:retry-worker`; packages `com.ghostserializer.sync.*` are now `com.retryjournal.*`; every `GhostSync*`-prefixed class is now `RetryJournal*` (facade `RetryJournal`, `RetryJournalEngine`, `RetryJournalRuntime`, `RetryJournalOfflineQueuePlugin`, etc). The sample tree `sync-sample` is now `retry-sample`. Maven coordinates stay under the already-verified `com.ghostserializer` publishing namespace (`com.ghostserializer:retry-journal` / `com.ghostserializer:retry-worker`) — only the artifact names changed, not the group.
- `retry-sample/composeApp` now wires background sync through `:retry-worker`'s `setupBackgroundSync()` (Android) and `registerRetryJournalBackgroundTask()` (iOS) instead of `kmpworkmanager`'s `@Worker`/Koin setup.
- `retry-sample`'s desktop embedded server toggle always starts in normal (non-chaotic) mode now; chaos mode is opt-in via the standalone server's `--args="chaos"`.
- Split the long root `README.md` into a short landing page plus a `docs/` wiki with one focused page per topic (installation, quick start, scheduling, background worker, runtime, uploads, dead letters, guarantees, development).
- Refactored `build.gradle.kts` files to extract publishing, Kover, and warning configurations into separate files under `gradle/` (`publishing.gradle`, `kover.gradle`, and `warnings.gradle.kts`).
- Renamed `fd` to `fileDescriptor` in `PlatformQueueFileLock` and added `INVALID_FILE_DESCRIPTOR` constant.

### Fixed

- Race conditions in `RetryJournalRuntimeTest` concurrent flush tests using `CompletableDeferred` instead of timing-dependent delays.
- Target-specific compilation errors on iOS targets (resolved `timeoutInterval` assignment in Darwin engine, POSIX `open` argument mapping, and `Dispatchers.IO` visibility).
- KSP code generation `@OptionalExpectation` errors on native/iOS targets via a `suppressGhostKspWarnings` post-processing Gradle task.
- `retry-sample` on Android: cleartext HTTP to the local chaos/normal server was silently blocked (`targetSdk 28+` blocks it by default) — the app could never detect the server was up. Added `android:usesCleartextTraffic="true"` (this sample is never published, so app-wide is fine here).
- `retry-sample` on Android: `DemoActionBar`'s Sync button rendered off-screen on phone-width windows — it now stacks below Upload/Send under `AppDimens.ACTION_BAR_COMPACT_BREAKPOINT` (600.dp) instead of assuming there's always room for three buttons in one row.
- `retry-sample` on Android: content drew under the status bar/camera cutout with edge-to-edge enabled by default (`targetSdk 35+`) — added `safeDrawingPadding()`.

---
