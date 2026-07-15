# Guarantees & honest limits

**Designed for:** offline / flaky mobile networks, one queue file per app, cooperative processes (UI + background worker), local or reliable storage.

| ✅ Strong | ⚠️ Know this |
|---|---|
| Survives app kill mid-queue | **At-least-once** delivery — plan for duplicates on critical mutations |
| Two processes won't double-replay the same head entry | **You** must call `flush()` after reconnect — not automatic network listening |
| Corrupt tail records recovered via scan | Default **64 MiB** max per field — raise `maxRecordFieldSize` if needed |
| Slow uploads keep replay claims alive (renewal during send) | **4xx** → dead letter, not retry forever |
| | Advisory file locks — exotic network filesystems (some NFS setups) may differ |

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
