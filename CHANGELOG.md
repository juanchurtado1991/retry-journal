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
- 76 JVM unit tests covering queue, engine, plugin, DLQ, and header storage

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
