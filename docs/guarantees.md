# Guarantees & honest limits

**Designed for:** offline / flaky mobile networks, one queue file per app, cooperative processes (UI + background worker), local or reliable storage.

| ✅ Strong | ⚠️ Know this |
|---|---|
| Survives app kill mid-queue | **At-least-once** delivery — plan for duplicates on critical mutations |
| Two processes won't double-replay the same head entry | **You** must call `flush()` after reconnect — not automatic network listening |
| Corrupt tail records recovered via scan | Default **64 MiB** max per field — raise `maxRecordFieldSize` if needed |
| Slow uploads keep replay claims alive (renewal during send) | **4xx** → dead letter, not retry forever |
| | Advisory file locks — exotic network filesystems (some NFS setups) may differ |

## Installing alongside Ktor's own `HttpRequestRetry`

`RetryJournalOfflineQueuePlugin` and Ktor's built-in [`HttpRequestRetry`](https://ktor.io/docs/client-retry.html) both hook the same extension point (`HttpSend`), and installation order controls which one sees a failure first.

If `HttpRequestRetry` is installed **before** `RetryJournalOfflineQueuePlugin`, its default retry policy (`retryOnExceptionOrServerErrors`) retries on *any* exception it doesn't recognize as a timeout — including `OfflineQueuedException`. That means every one of its internal retry attempts re-enters this plugin's failure handling, enqueueing another copy of the same mutating request: up to `maxRetries + 1` duplicate entries on disk for a single logical send, each later replayed independently.

If you use both, either:
- Install `RetryJournalOfflineQueuePlugin` **before** `HttpRequestRetry`, so this plugin's `OfflineQueuedException` is the one that reaches the caller instead of being fed back into Ktor's retry loop, or
- Configure `HttpRequestRetry`'s `retryIf { _, response -> ... }` / exception predicate to exclude `OfflineQueuedException` explicitly.

## What you build vs what the library gives you

| You provide | retry-journal provides |
|---|---|
| When to call `flush()` (network callback, worker, UI) | Durable queue on disk |
| UX for `OfflineQueuedException` | Capture headers + body on connectivity failure |
| Idempotent server APIs (recommended) | Ordered replay via `flush()`, DLQ, crash recovery |
| Ktor `HttpClient` config (auth, JSON, etc.) | `getHeadState()` for UI inspection |

Full change history: [CHANGELOG.md](../CHANGELOG.md).

---

[← Back to docs](README.md)
