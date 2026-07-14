# Changelog

All notable changes to `ghost-sync` are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `FrozenHttpHeaders` — flat `List` pair storage (no `Map`) for queued HTTP headers
- `HeaderDispatch` — compare-chain dispatch for replay (`Content-Type`, `Content-Length`, other)
- Cross-process advisory file lock (`<queuePath>.lock`) for shared queue files
- `BodyCaptureException` — fail-closed when request body bytes cannot be captured
- GitHub Actions CI (`ciTestJvm` + multi-target compile)
- Maven Central publish wiring via `com.vanniktech.maven.publish`
- Apache 2.0 LICENSE
- 169 JVM unit tests covering queue, engine, plugin, DLQ, and header storage
- `ReplayClaim` — cross-process `<queuePath>.replay-claim` marker so two processes sharing a queue file cannot both replay the same head entry and duplicate a non-idempotent POST
- `LifecycleGate` — serializes `enter`/`leave` against `close()` on [DiskQueue], [GhostSyncEngine], and [GhostOfflineQueuePlugin], eliminating the TOCTOU window where `close()` could proceed while a new operation was starting
- `GhostSyncEngine.getEntryAndStatus()` reads head entry and queue status under the same replay [Mutex] as `flush()`, closing duplicate manual-replay footguns
- `EntryReplayResult` — sealed outcome for [GhostSyncEngine.getEntryAndStatus] (`Empty`, `HeadBlocked`, `ReplayFailed`, `Ready`) instead of a nullable snapshot that conflated empty and blocked heads

### Fixed
- `DiskQueue.enqueue` rejects records whose packed on-disk length would overflow the 26-bit index **before** writing
- `DiskQueue` tombstones unreadable head entries so `peek()` cannot stall behind corrupt data
- `RecordCodec` treats Ghost deserialize failures as invalid records instead of crashing recovery
- CRC recovery scan advances by `recordLength` on invalid records (no O(n) byte-by-byte fallback)
- `DeadLetterQueue` recovery skips re-enqueue when an identical entry is already on the main queue
- `DeadLetterQueue.retry` is serialized with a mutex so concurrent retries cannot duplicate entries
- `GhostSyncEngine` treats 408/429 as retry-worthy (stop early, keep on main queue)
- `GhostSyncEngine` stops early when dead-letter persistence fails instead of dropping the entry
- `GhostSyncEngine` stops early on unexpected replay faults without dead-lettering
- `GhostOfflineQueuePlugin` captures multi-valued headers with a record separator (`\u001e`)
- `GhostOfflineQueuePlugin` fails closed on body capture errors instead of enqueueing empty payloads
- `GhostOfflineQueuePlugin` handles `NoContent` bodies (header-only requests)
- `GhostOfflineQueuePlugin` serializes header/body capture so concurrent failed requests can no longer cross-contaminate each other's queued headers
- JVM/Android `DiskQueue` instances sharing a queue file no longer crash with `OverlappingFileLockException` when driven from real parallel threads in the same process
- `DeadLetterQueue.record` is idempotent for an identical replayed entry, closing a crash window that could duplicate a dead-letter record
- `GhostSyncEngine.flush` rejects an `HttpClient` with `GhostOfflineQueuePlugin` installed instead of silently duplicating entries on replay failure
- `GhostSync.close`/`DiskQueue.close` keep closing the rest of their owned resources instead of abandoning them behind the first one that throws
- JVM/Android `PlatformQueueFileLock.acquire()` releases the intra-JVM lock if opening/locking the underlying file fails partway through, instead of leaving every future acquirer for that path blocked forever
- `DiskQueue` crash recovery no longer truncates away everything after a corrupted tombstone — `RecordScanResult`'s reused length field was left stale on a tombstone CRC mismatch, sometimes skipping far enough to overshoot end-of-file and truncate legitimately valid trailing records
- `DeadLetterQueue` dedup (`record()` and retry-journal recovery) now compares headers too, not just method/url/body — two requests that only differed by e.g. an `Authorization` header no longer collapse into one dead-letter entry
- `GhostSyncEngine` falls back to sending the raw body instead of throwing when a stored `Content-Type` no longer parses — previously this permanently stalled the whole queue, since `flush()` always retries the same oldest entry first
- `GhostSyncEngine.getStatus` rejects an `HttpClient` with `GhostOfflineQueuePlugin` installed, the same guard `flush()` already had — closes the same duplicate-enqueue footgun for a caller driving its own `getEntry`/`getStatus` loop
- `DeadLetterQueue` retry-journal recovery deletes a journal it can't parse instead of leaving it to be re-attempted (and re-fail) on every future startup
- `DeadLetterQueue.readJournal` bounds every length-prefixed field before using it to size a read or allocation, closing an `OutOfMemoryError` path a corrupted journal's header count could otherwise reach
- `DiskQueue` no longer relies on the caller using a blocking-friendly dispatcher — every operation now dispatches onto the platform's own `Dispatchers.IO` internally instead of running blocking file I/O on whatever dispatcher happened to call it
- `DiskQueue.close`/`GhostSync.close` throw `IllegalStateException` instead of proceeding if an operation is still in flight on the same instance, closing the "closed while still in use" footgun that used to only be documented, not enforced
- Android `PlatformQueueFileLock` no longer uses `java.nio.file`'s `Path`-based `FileChannel.open`/`Files`/`Paths` (API 26) or `ConcurrentHashMap.computeIfAbsent` (API 24) — both would have failed to link on this module's declared `minSdk` 21, crashing on the very first `DiskQueue` operation on a real API 21-25 device. Replaced with the `java.io.File`/`RandomAccessFile`-based equivalents, API 1 since forever. Caught by `:ghost-sync:lintDebug`.
- `DiskQueue` crash recovery no longer truncates trailing valid records past a corrupted *live* record — the previous tombstone-only fix left `RecordScanResult.recordLength` stale on three other invalid-record paths (unrecognized kind byte, and `scanLivePayload`'s `metaLen`/`bodyLen` range checks); it's now reset at the top of every `scanRecord()` call instead of patched branch by branch
- `DiskQueue.get`/`peek`/`peekAll` no longer trust the in-memory index's offset blindly — a record read back with a *different* sequence id than the index expected (a recovery bug, or a tampered file) is now treated as unreadable and scrubbed, instead of silently returned to the caller mislabeled under the wrong id
- `GhostSync.close` throws `IllegalStateException` instead of proceeding while `flush()` is still replaying a request — `DiskQueue`'s own in-flight guard only covers the brief `peek()`/`remove()` calls around a replay, not the network round-trip itself, so `close()` could previously call `replayClient.close()` out from under an in-flight request
- `RetryJournal.read` catches `Throwable`, not just `Exception` — a corrupted journal's header count is bounds-checked per field, but not summed, so a run of many large-but-in-range header lengths could still throw `OutOfMemoryError`, which the old `catch (_: Exception)` would have let propagate up through recovery
- `HttpReplayer` falls back to a custom [HttpMethod] when a stored method string no longer parses — previously this permanently stalled the whole queue, the same failure mode `Content-Type` had before `parseContentTypeOrNull`
- `GhostSyncEngine.flush` and `getStatus` share a [Mutex] so concurrent callers cannot replay the same head entry twice and duplicate non-idempotent POSTs
- `GhostSyncEngine.hasActiveReplaySession` covers the full span of any `flush()` or `getStatus()` call, including an in-flight network round-trip — `GhostSync.close()` now refuses to proceed while either is active, not just while `GhostSync.flush()` is running
- `GhostSync.close()` refuses to proceed while a request is still in flight on [client] — previously only `flush()` on `replayClient` was guarded
- `DiskQueue` triggers compaction after tombstoning corrupt entries via `peek()` or `scrubUnreadableEntriesLocked()`, not only after an explicit `remove()` — dead bytes from scrub no longer linger until a unrelated delivery
- `DeadLetterQueue` dedup compares headers order-insensitively — two requests that only differed in header insertion order no longer create duplicate dead-letter entries
- `RequestCapture` captures headers in a single pass over `request.headers.entries()` instead of counting then iterating twice
- `GhostOfflineQueuePlugin` treats wrapped [IOException]s in the cause chain as connectivity failures worth queueing, not only a top-level [IOException]
- `GhostOfflineQueuePlugin` tracks in-flight request count so `GhostSync.close()` can refuse to proceed while `client` is mid-request
- `HttpReplayer` returns a synthetic 400 when a stored URL no longer parses (pre-flight [URLBuilder] check), routing the entry through dead-letter instead of permanently stalling the queue behind it
- `RequestCapture` reads channel body bytes before closing the write channel, avoiding read-after-close on slow producers
- `GhostSync.close()` calls `closeForShutdown()` on the engine and plugin before closing Ktor clients, so lifecycle gates reject new work while in-flight replay/requests finish
- `DiskQueueRecovery` clears stale `ReplayClaim` files on open so a crash mid-replay does not block the queue forever
- `DiskQueue.removeLocked` persists the tombstone before updating the in-memory index and truncates the file back on flush failure — previously a failed tombstone write could drop the entry from the index while the live record remained on disk, and a partial buffered write could leave a durable tombstone without updating the index
- `DiskQueue.completeHeadReplay` validates that [entryId] is still the queue head, clears the [ReplayClaim] in `finally`, and stops early when removal fails instead of leaving a stale claim behind
- `GhostSyncEngine.getEntry` runs under the same [replayMutex] as `flush()`/`getStatus()`; `getStatus()` now claims the head via [ReplayClaim] and rejects entries that are no longer head — closes the TOCTOU duplicate-delivery path between separate `getEntry()` + `getStatus()` calls and cross-process manual replay
- `HttpReplayer` pre-validates stored URLs with [URLBuilder] instead of catching every [Exception] during replay as a synthetic 400 — runtime transport/client faults stop early again instead of being dead-lettered
- `RequestCapture` bounds streamed body reads to [DiskQueue.maxRecordFieldSize] before enqueue, failing closed with [BodyCaptureException] instead of OOMing on oversized uploads
- `ReplayClaim.write` uses temp-file + atomic rename so a crash mid-write cannot be misread as "no claim" and allow a duplicate replay
- `DiskQueueCompactor` rejects on-disk records whose sequence id does not match the index entry, matching [readLiveEntryAtLocked]'s guard

### Fixed (bug hunt round 8)
- `GhostSyncEngine.getStatus` clears the [ReplayClaim] on any replay failure, not only [IOException] — a [RuntimeException] no longer leaves the head blocked for up to 15 minutes
- `DiskQueue.remove` rejects removing an entry that another process has actively claimed for replay; [completeHeadReplay] still removes under its own claim via [removeLocked]
- `DiskQueue.completeHeadReplay` clears the [ReplayClaim] in `finally` even when validation throws before removal starts
- `DeadLetterQueue` recovery completes an in-flight `retry()` when the journal exists but the entry is still in dead-letter storage (crash after journal write, before `storage.remove`)
- `DiskQueueCompactor.planCompaction` aborts without swapping when an indexed record's on-disk sequence id mismatches, instead of silently dropping the live entry
- `ReplayClaim.isStale` treats future-dated claim timestamps as stale so a corrupt clock value cannot block the queue forever
- `RetryJournal.write` uses temp-file + atomic rename, matching [ReplayClaim]'s durability pattern
- `RequestCapture` copies [OutgoingContent.ByteArrayContent] bytes instead of retaining a reference to the caller's buffer

### Fixed (bug hunt round 9)
- `DeadLetterQueue.discard` and `retry` share [retryMutex] so a concurrent discard cannot be undone by an in-flight retry re-enqueuing onto the main queue
- `DeadLetterQueue.retry` runs inline journal recovery when `mainQueue.enqueue` fails, instead of marooning the entry until a new process starts
- `DeadLetterQueue.record` deduplicates under [retryMutex] so concurrent identical records cannot create duplicate dead-letter entries
- `GhostSyncEngine.getEntry` respects the shutdown [LifecycleGate], matching `flush()` and `getEntryAndStatus()`
- `DiskQueue.enqueue` truncates the file back to its pre-append offset when the append flush fails, matching tombstone rollback in [removeLocked]

### Fixed (bug hunt round 10)
- `DeadLetterQueue.discard` deletes the retry journal before recovery so an orphan journal cannot resurrect a discarded entry onto the main queue
- `DeadLetterQueue.recoverSingleJournalFile` deletes retry journal files whose id suffix is not a valid number
- `DeadLetterQueue` uses [LifecycleGate] for in-flight operations; [GhostSync.close] calls `closeForShutdown()` on the DLQ before closing queues
- `GhostOfflineQueuePlugin` rethrows disk enqueue failures instead of masking them as [OfflineQueuedException]
- `HttpReplayer.send` drains the response body in `finally` so replay does not leak Ktor connections

### Fixed (cross-process index, replay claims)
- `DiskQueue` tracks a monotonic `.gen` sidecar instead of mtime+size alone so cross-process index refresh cannot miss same-length writes
- `DiskQueue.completeHeadReplay` requires an active [ReplayClaim] matching the entry — no head removal without a prior `prepareHeadForReplay()`
- `GhostSyncEngine` renews the cross-process replay claim every five minutes during an in-flight HTTP round-trip so slow uploads are not capped by the stale-claim window; stale claims now mean "no renewal for 30 minutes" (crash artifact), not a fixed upload time limit

### Fixed (bug hunt round 11 — final)
- `DiskQueueScrubOps.scrubUnreadableEntriesLocked` bumps the `.gen` sidecar after bulk tombstoning so a second process cannot keep a stale in-memory index and miss scrubbed slots
- `DiskQueue.get` tombstones unreadable index slots (CRC/sequence mismatch) instead of returning `null` while leaving ghost entries that block `size()`/`peek()`
- `GhostSyncEngine.handleDeadLetterDelivery` rethrows [CancellationException] after clearing the replay claim instead of treating cooperative cancellation as a flush stop
- `DeadLetterQueue.retry` rethrows [CancellationException] instead of running inline journal recovery as if enqueue had failed with I/O

### Fixed (bug hunt round 12 — pre-release)
- `DiskQueueIndexSync.refreshIfNeededLocked` rescans when on-disk file size diverges from the cached length even if `.gen` unchanged — covers crash after append flush but before generation bump
- `DeadLetterQueue.retry` and `discard` serialize cross-process via `.dlq-retry.lock` so two processes cannot duplicate a retry onto the main queue
- `GhostSyncEngine.withReplayClaimRenewal` uses `cancelAndJoin()` so a renewal coroutine cannot resurrect a claim after abort/complete
- Scrub, `get()`, and head-scan skip tombstoning sequence ids with a non-stale active [ReplayClaim]
- `claimHeadForReplay` returns [HeadReplayPrepareResult.HeadBlocked] for any non-stale claim, not only when it matches the head sequence id

### Fixed (bug hunt round 13)
- `DeadLetterQueue.record` and retry-journal recovery share `.dlq-ops.lock` cross-process so concurrent record/recovery cannot duplicate DLQ or main-queue entries
- `claimHeadForReplay` clears orphan replay claims whose sequence id is no longer in the live index instead of blocking flush for up to 30 minutes
- `DiskQueue.compactIfNeededLocked` skips compaction while a non-stale [ReplayClaim] is active
- `DiskQueue.size` counts only readable entries, matching `peekAll`/`peekIds` when scrub skips claimed unreadable slots
- `HttpReplayer` drops hop-by-hop headers (`Transfer-Encoding`, `Host`, `Connection`, etc.) on replay — bodies are materialized byte arrays
- `GhostSyncEngine.getHeadState` distinguishes empty queue from head blocked by a cross-process replay claim; `getEntry()` docs updated accordingly

### Changed
- `DiskQueue` and disk-operation helpers moved to `com.ghostserializer.sync.queue.disk` package (public API unchanged — same class names, new package path)
- sync-sample demo UI split into `ui/` subpackages (`components/`, `screen/`, `state/`, `action/`, `effects/`, `theme/`, `model/`)

### Fixed (bug hunt round 16)
- `finalizeHeadReplay` rejects non-head entries (`NotHead`) before writing a delivery journal — prevents silent queue drops when a stale `QueueEntry` is finalized
- `getHeadState` reports `PendingLocalRemoval` when the head has a delivery journal, and only reports `Blocked` for replay claims on the current FIFO head (not orphan non-head claims)
- `getStatus` validates the entry is still head before claiming; stale-entry errors no longer abort the real head's replay claim
- `finalizeHeadReplay` clears a stale delivery journal when abandoning a retry-worthy status (`LeftOnQueue`)
- `DeliveryJournal` partial parse without a valid outcome is treated as absent (re-send HTTP) instead of defaulting to delivered
- `GhostSyncRuntime.lastKnownOnline` is `@Volatile` for cross-thread visibility

### Fixed (bug hunt round 15)
- `claimHeadForReplay` clears an active head replay claim when a `DeliveryJournal` is pending for that sequence — recovery no longer blocks for up to 30 minutes behind a stale claim
- `GhostSyncEngine.getEntryAndStatus` / `getStatus` honor a pending delivery journal and skip HTTP, matching `flush()` recovery semantics
- `GhostSyncEngine.finalizeHeadReplay` — public API for manual replay loops to apply 2xx / dead-letter outcomes after `getEntryAndStatus` or `getStatus`
- `DeliveryJournal` — magic header + CRC validation; corrupt journals surface as `CorruptPending` so recovery retries local removal without re-sending HTTP
- `GhostSyncEngine.flush` buffers `onProgress` outside `replayMutex` — progress callbacks can safely call back into the engine without deadlocking
- `GhostSync.processFlushMutex` shared with `GhostSyncRuntime` — direct `ghostSync.flush()` and `runtime.flush()` serialize in the same process

### Changed
- README positioning — Room KMP is a good fit for app state; ghost-sync remains the dedicated offline HTTP mutation queue

### Fixed (bug hunt round 14)
- `DeliveryJournal` — two-phase commit after HTTP 2xx or successful dead-letter `record()`: durable `.delivery-pending` sidecar lets the next `flush()` retry local removal without re-sending HTTP, closing duplicate-delivery when tombstone flush fails after server success
- `FlushResult.persistenceFailed` — surfaces when the server side-effect succeeded but queue removal did not finish (journal left for recovery)
- `claimHeadForReplay` clears non-head orphan replay claims instead of blocking the FIFO head for up to 30 minutes
- `validateCompleteHeadReplayLocked` rejects stale replay claims
- `HeaderDispatch` skips additional hop-by-hop headers (`Keep-Alive`, `Proxy-Connection`, `Upgrade`, `Proxy-Authorization`) on replay
- `RetryJournal.read` bounds cumulative parsed bytes across all length-prefixed fields
- `GhostSyncRuntime.shutdown` joins the connectivity collector before cancelling the supervisor scope

### Added
- `DeliveryJournal` — `.delivery-pending` sidecar for two-phase delivery commit (HTTP/DLQ succeeded, local removal retried on next flush)
- `FlushResult.persistenceFailed` — flag when delivery journal remains after a failed tombstone write
- `GhostSyncRuntime` — lifecycle- and concurrency-aware coordinator: serialized `flush()`, optional auto-flush from an app-supplied `Flow<Boolean>` connectivity signal, `flushWhenOnline()`, and `shutdown()`; factories `GhostSync.createRuntime(...)` and `GhostSyncRuntime.createForEngine(...)` for manual engine wiring

### Known limitations
- iOS targets compile but are **not yet verified on macOS** — see [`ios_techdebt.md`](ios_techdebt.md)

## [0.1.0] - 2026-07-13

### Added
- `GhostSync` facade — wires `DiskQueue`, `DeadLetterQueue`, `GhostSyncEngine`, and Ktor clients
- `GhostOfflineQueuePlugin` — captures failed requests on `IOException` and persists to disk
- `DiskQueue` — append-only crash-safe FIFO with CRC32 recovery and compaction
- `GhostSyncEngine` — replay with 2xx deliver / 4xx dead-letter / 5xx retry semantics
- `DeadLetterQueue` — inspect, retry, and discard rejected requests
- KMP targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`

[0.1.0]: https://github.com/ghostserializer/ghost-sync-kmp/releases/tag/v0.1.0
