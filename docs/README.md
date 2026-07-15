# retry-journal docs

Everything you need to use `retry-journal` in a Kotlin Multiplatform app, one focused page per topic.

## Getting started

1. **[Installation](installation.md)** — add the dependency
2. **[Quick start](quick-start.md)** — create the queue, send requests, flush
3. **[Scheduling `flush()`](scheduling.md)** — DIY patterns, or the out-of-the-box worker

## Going deeper (optional)

These aren't required to get started — read them when you need them.

- **[Background worker (`:retry-worker`)](background-worker.md)** — WorkManager / BGTaskScheduler with zero glue code
- **[RetryJournalRuntime](runtime.md)** — serialize `flush()` across multiple callers
- **[File & image uploads](uploads.md)** — multipart uploads captured and replayed offline
- **[Dead letters](dead-letters.md)** — what happens when the server says no
- **[Guarantees & honest limits](guarantees.md)** — what's strong, what to watch out for
- **[Building, testing & publishing](development.md)** — contributing to retry-journal itself

## Also see

- **[Sample app](../retry-sample/README.md)** — a working Compose Multiplatform demo (desktop/Android/iOS)
- **[CHANGELOG](../CHANGELOG.md)**

[← Back to the main README](../README.md)
