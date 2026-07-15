# How this compares to Tape / Room

Libraries like [Square Tape](https://github.com/square/tape) solve a **different** problem: a generic `byte[]` FIFO on disk (`QueueFile` / `ObjectQueue`). You still build capture, retry policy, HTTP replay, dead letters, and multi-process coordination yourself.

**retry-journal is the sync layer**, not a queue primitive:

| Tape-style file queue | retry-journal |
|---|---|
| Arbitrary `byte[]` blobs | Frozen HTTP requests — method, URL, headers, body bytes |
| Android / Java | **KMP** — Android, iOS, JVM |
| You wire networking & retry policy | **Ktor plugin** captures on `IOException` + **replay engine** applies 2xx / 4xx DLQ / 5xx retry |
| In-place header rewrites (Tape's own docs warn about corruption on conventional filesystems) | **Append-only** records + atomic rename — no in-place header mutation |
| Single-process assumption | **Cross-process** file locks + replay claims (app + background worker) |
| No delivery semantics | **Per-sequence delivery journal** — two-phase commit so a crash after server 2xx doesn't force a duplicate POST |

Room KMP is great for **app state** (users, settings, cached reads). retry-journal owns the **offline mutation queue** — the ordered pipe of POSTs/PUTs that must survive no signal and replay when you're back.

Tape is a fine brick if you want to build all of the above from scratch. retry-journal gives you the stack; you only decide **when** to call `flush()`.

---

[← Back to docs](README.md)
