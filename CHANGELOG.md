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
- 50 JVM unit tests covering queue, engine, plugin, DLQ, and header storage

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

### Known limitations
- iOS targets compile but are **not yet verified on macOS** — see [`ios_techdebt.md`](ios_techdebt.md)
- `DiskQueue`/`GhostSync.close()` are not synchronized with in-flight operations on the same instance — documented in `DiskQueue`'s "Threading contract" doc rather than fixed, since making `close()` participate in the coroutine `Mutex` would require making it `suspend`, breaking the `Closeable`-style contract it needs to satisfy. Callers must not call `close()` while another operation on the same instance is in flight.
- `DiskQueue` does blocking file I/O on whatever dispatcher the caller uses it from; run it from `Dispatchers.IO` (or equivalent), not `Dispatchers.Default`

## [0.1.0] - 2026-07-13

### Added
- `GhostSync` facade — wires `DiskQueue`, `DeadLetterQueue`, `GhostSyncEngine`, and Ktor clients
- `GhostOfflineQueuePlugin` — captures failed requests on `IOException` and persists to disk
- `DiskQueue` — append-only crash-safe FIFO with CRC32 recovery and compaction
- `GhostSyncEngine` — replay with 2xx deliver / 4xx dead-letter / 5xx retry semantics
- `DeadLetterQueue` — inspect, retry, and discard rejected requests
- KMP targets: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`

[0.1.0]: https://github.com/ghostserializer/ghost-sync-kmp/releases/tag/v0.1.0
