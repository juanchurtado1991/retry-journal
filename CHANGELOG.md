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

### Known limitations

- iOS targets compile but are **not yet verified on macOS** — see [`ios_techdebt.md`](ios_techdebt.md)

[1.0.0]: https://github.com/juanchurtado1991/ghost-sync-kmp/releases/tag/v1.0.0
