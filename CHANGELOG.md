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
