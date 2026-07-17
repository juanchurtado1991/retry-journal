# Changelog

All notable changes to `retry-journal` are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Fixed

- `retry-sample`: a multi-item `flush()`'s queue-chip animation could revert a chip's Delivered/DeadLettered status back to Pending, or resurrect an already-removed chip, when two chips' `FlushProgress` callbacks landed within the same 400ms animation window (`RetryJournalEngine.flush` buffers and replays them back-to-back) — the delayed removal step was applying against a stale snapshot of the chip list instead of its current state.

### Changed

- `retry-sample`: removed dead code and unused dependencies found during a repo-wide sanity pass — five orphaned `AppStrings` constants for a manual connectivity toggle that no longer exists (connectivity is now driven by the server health poll), a redundant `if` branch in `QueueSnapshotActions.chipStatusForEntry` returning the same value on both sides, unused `testImplementation` dependencies in `:retry-sample:server` (no `src/test` exists), an unused `kotlinx.serialization.json` dependency in `:retry-sample:composeApp` (the app exclusively uses Ghost), and five unused entries in `gradle/libs.versions.toml` (`androidx-lifecycle-viewmodel[-compose]`, `kotlin-compile-testing`, `junit-jupiter-api`, `junit-engine`). Also fixed `retry-sample/iosApp/README.md`'s file table, which still referenced a standalone `AppDelegate.swift` — `AppDelegate` has lived inside `iosAppApp.swift` since the project was scaffolded.

## [1.1.0] - 2026-07-17

Selective offline queueing, plus reliability fixes across the disk queue, dead-letter recovery, cross-process locking, and the offline queue plugin found and closed since 1.0.0.

### Added

- Selective offline queueing: connectivity failures are now queued by default only for mutating methods (`POST`/`PUT`/`PATCH`/`DELETE`) — `defaultShouldEnqueue`. `GET`/`HEAD`/`OPTIONS` are skipped by default since nothing is left waiting for a delayed response by the time a later `flush()` resends it.
  - `RetryJournalOfflineQueueConfig.shouldEnqueue` lets you replace the default rule with your own global policy.
  - `RetryJournalHeaders.ENQUEUE_OVERRIDE` (`X-Retry-Journal-Enqueue: true`/`false`) is a per-request override that takes priority over both — reachable from codegen clients (Ktorfit, Retrofit-style annotation interfaces) via `@Headers`/`@Header`, since they never expose direct access to `HttpRequestBuilder`. The header is stripped before the request is ever sent, so it never reaches the server and never needs filtering before persistence or replay.

### Fixed

- `PlatformQueueFileLock.ensureExists` unconditionally opened and closed a throwaway probe channel on every `acquire()`, even for an already-locked file — the JDK's `FileLock` docs warn this can silently release *another* channel's still-held lock on some platforms. It now only opens a channel when the file doesn't exist yet, when nothing could hold a lock on it.
- `PlatformQueueFileLock`'s intra-JVM lock key could still diverge for two different dangling symlinks pointing at the same not-yet-created target, reintroducing the `OverlappingFileLockException` race the earlier canonical-path fix was meant to close; `ensureExists` now uses the same non-`EXCL` open semantics as the real lock acquisition instead of `Files.createFile`.
- A `RetryJournalOfflineQueueConfig.shouldEnqueue` predicate that threw could stop a request from being attempted at all, even over a healthy connection, since it ran before `execute()`; it's now caught and falls back to the default method-based rule.
- Setting `RetryJournalHeaders.ENQUEUE_OVERRIDE` more than once on the same request (e.g. a base header plus a per-call override) used to keep the *first* value instead of the last, silently ignoring the override.
- `defaultShouldEnqueue` compared the `HttpMethod` instance case-sensitively — a request built with `HttpMethod("post")` directly instead of Ktor's `post()` DSL was silently treated as non-mutating and never queued.
- Removed leftover debug `println` calls in `RetryJournalOfflineQueuePlugin` that printed on every intercepted request in production, including full request URLs.
- `HeadReplayExecutor.finishDeliveredFromJournal` could let an exception escape `flush()` unhandled instead of resolving to a `FlushResult`, and left the delivery journal file orphaned on disk.
- `FlushResult.persistenceFailed` could be reported as `false` even when a delivery/dead-letter outcome had already been durably journaled before local cleanup failed.
- `DiskQueue.enqueue` could silently corrupt a record's offset and length once the queue file grew past ~16 GiB — the packed index now rejects the write instead (`QueueFileTooLargeException`).
- Crash recovery could lose every record after one whose `metaLen`/`bodyLen` field was corrupted but still in-range — the scanner no longer trusts an unverified length to skip ahead on a CRC mismatch.
- `DiskQueueCompactor` could silently drop a live entry (writing a tombstone in its place, without a trace) when the in-memory index and on-disk bytes disagreed about something other than the entry's sequence id; it now aborts the compaction cycle instead, matching the existing sequence-id-mismatch behavior.
- `LiveEntryIndex` had no upper bound on the array it would allocate for the gap between the oldest and newest live sequence id — a permanently stuck head entry alongside heavy throughput could OOM the process; it now fails with a diagnosable exception instead.
- `DeadLetterQueue.discard()` could throw `OverlappingFileLockException` and leak a file descriptor when a pending retry journal for a *different* id needed recovering first.
- `PlatformQueueFileLock`'s intra-process lock was keyed by a purely lexical path string — a symlink or case-insensitive filesystem alias pointing at the same real file could bypass it.
- `ReplayClaim.isStale` treated any backward wall-clock jump past 60s as claim corruption, releasing an active claim and risking a duplicated non-idempotent POST; it now only treats an implausibly large jump (beyond the existing stale window) as corruption.
- `RetryJournalRuntime.shutdown()` marked itself done before running the close action — a failed close (e.g. a replay still in flight) permanently poisoned the instance instead of leaving it retryable.
- `RequestCapture` could wrap a genuine coroutine cancellation as `BodyCaptureException` instead of letting it propagate.
- `RequestCapture` held its lock for the full body capture, not just the header capture that actually needs it — a slow multipart/streaming body from one failing request could block every other concurrently failing request's header capture.
- `RetryJournal.close()` could silently lose all but the last exception when multiple owned resources failed to close; every failure is now preserved via `addSuppressed`.
- `:retry-worker` iOS: `expirationHandler` and a run's own successful completion could both reschedule and complete the same `BGTask`, double-completing it and letting a stale reschedule stomp the correct one.
- `:retry-worker` Android: a `doWork()` call landing before `setupBackgroundSync()` registered the runtime now goes through the normal short-backoff retry budget instead of failing the whole period outright.

## [1.0.0] - 2026-07-15

First public release.

### Added

- `RetryJournal` + `RetryJournalOfflineQueuePlugin` — durable offline HTTP mutation queue for Ktor
- `RetryJournalRuntime` — serialized `flush()`, connectivity-driven auto-flush, `flushWhenOnline()`, `shutdown()`
- `RetryJournalEngine.flush()` + `getHeadState()` — the only public replay surface
- `HeadReplayExecutor` — internal state machine for head replay (`AwaitingReplay`, `AwaitingLocalRemoval`, `Blocked`, `Empty`)
- Per-sequence delivery journals — `<queuePath>.delivery-pending.<sequenceId>` two-phase commit after HTTP/DLQ success
- `DeadLetterQueue` — persistent dead-letter storage with retry and cross-process serialization
- `ReplayClaim` — cross-process head claim so two processes cannot duplicate a non-idempotent POST
- `DiskQueue` — append-only on-disk queue with crash recovery, compaction, and advisory file locking
- `LiveEntryIndex` — dense `LongArray`-backed in-memory index for `DiskQueue`'s live entries, ~90% smaller than the `LinkedHashMap<Long, Long>` it replaced (~8 bytes/entry vs. ~98 bytes/entry); see the [Performance report](docs/development.md#performance-report) for real JOL-measured numbers.
- `FrozenHttpHeaders` — flat header storage for faithful replay
- `RetryJournalScheduler` / `RetryJournalSchedulerConfig` (`:retry-journal`, package `scheduler/`) — the scheduler-agnostic contract that `:retry-worker` implements; any app can implement it directly instead.
- `:retry-worker` — new Maven module with an out-of-the-box background scheduler: `androidx.work` (WorkManager) on Android, `BGTaskScheduler` on iOS, a no-op on JVM/desktop. On both platforms, a failed/incomplete run reschedules sooner (`retryDelayMs`) for up to `maxRetryAttempts` in a row before falling back to the normal interval.
- `retry-sample:server` normal mode (default — every request succeeds) alongside the existing chaos mode (`--args="chaos"`), so a large batch can replay in one `flush()` without an artificial failure interrupting it partway through.
- Apache 2.0 LICENSE, Maven Central publish wiring, GitHub Actions CI
- 240+ JVM unit tests (across `:retry-journal` and `:retry-worker`), Kover coverage gate ≥90%, mutation testing (`pitestCore`), a real cross-process file-lock test, differential fuzz tests (record codec, `LiveEntryIndex`), load tests, and Android instrumented tests (`connectedDebugAndroidTest`) against a real `Context`/ART runtime.
- `performanceReport` Gradle task — real, JIT-warmed/JOL-measured speed and memory numbers for `DiskQueue`, documented in [docs/development.md](docs/development.md#performance-report).
- Demo GIF on the root README.

### Changed

- **Renamed the whole project from GhostSync to RetryJournal.** Modules `:ghost-sync` / `:ghost-sync-worker` are now `:retry-journal` / `:retry-worker`; packages `com.ghostserializer.sync.*` are now `com.retryjournal.*`; every `GhostSync*`-prefixed class is now `RetryJournal*` (facade `RetryJournal`, `RetryJournalEngine`, `RetryJournalRuntime`, `RetryJournalOfflineQueuePlugin`, etc). The sample tree `sync-sample` is now `retry-sample`. Maven coordinates stay under the already-verified `com.ghostserializer` publishing namespace (`com.ghostserializer:retry-journal` / `com.ghostserializer:retry-worker`) — only the artifact names changed, not the group.
- `retry-sample/composeApp` now wires background sync through `:retry-worker`'s `setupBackgroundSync()` (Android) and `registerRetryJournalBackgroundTask()` (iOS) instead of `kmpworkmanager`'s `@Worker`/Koin setup.
- `retry-sample`'s desktop embedded server toggle always starts in normal (non-chaotic) mode now; chaos mode is opt-in via the standalone server's `--args="chaos"`.
- Split the long root `README.md` into a short landing page plus a `docs/` wiki with one focused page per topic (installation, quick start, scheduling, background worker, runtime, uploads, dead letters, guarantees, development).
- Refactored `build.gradle.kts` files to extract publishing, Kover, and warning configurations into separate files under `gradle/` (`publishing.gradle`, `kover.gradle`, and `warnings.gradle.kts`).
- Renamed `fd` to `fileDescriptor` in `PlatformQueueFileLock` and added `INVALID_FILE_DESCRIPTOR` constant.
- `DiskQueue`'s scrub/count/peek-ids paths now use the existing CRC-only scan codec instead of fully materializing and Ghost-deserializing each entry just to check whether it's readable.

### Fixed

- Race conditions in `RetryJournalRuntimeTest` concurrent flush tests using `CompletableDeferred` instead of timing-dependent delays.
- Target-specific compilation errors on iOS targets (resolved `timeoutInterval` assignment in Darwin engine, POSIX `open` argument mapping, and `Dispatchers.IO` visibility).
- KSP code generation `@OptionalExpectation` errors on native/iOS targets via a `suppressGhostKspWarnings` post-processing Gradle task.
- `RequestCapture.ensureHeaderScratch()` could silently drop captured headers under certain growth patterns.
- `retry-sample`'s iOS Xcode project wouldn't reliably build on a fresh clone: a hardcoded absolute path, a scheme visible only to the original author's machine (not `xcshareddata`), and a stale framework search path — all three fixed and verified with real `xcodebuild` runs.
- `retry-sample` on Android: cleartext HTTP to the local chaos/normal server was silently blocked (`targetSdk 28+` blocks it by default) — the app could never detect the server was up. Added `android:usesCleartextTraffic="true"` (this sample is never published, so app-wide is fine here).
- `retry-sample` on Android: `DemoActionBar`'s Sync button rendered off-screen on phone-width windows — it now stacks below Upload/Send under `AppDimens.ACTION_BAR_COMPACT_BREAKPOINT` (600.dp) instead of assuming there's always room for three buttons in one row.
- `retry-sample` on Android: content drew under the status bar/camera cutout with edge-to-edge enabled by default (`targetSdk 35+`) — added `safeDrawingPadding()`.
- Root README's demo GIF/comparison table used raw HTML for pixel-exact width, which Android Studio's Markdown previewer doesn't render — switched to plain Markdown for universal compatibility.

---
