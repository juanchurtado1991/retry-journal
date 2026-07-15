# Dead letters (server said no)

When replay gets a **non-retry-worthy 4xx**, the request moves to the dead letter queue instead of blocking everything behind it:

```kotlin
val failed = mutableListOf<DeadLetterEntry>()
retryJournal.deadLetterQueue.peekAll(failed)

// Let the user fix and retry
retryJournal.deadLetterQueue.retry(failed.first().id)

// Or discard permanently
retryJournal.deadLetterQueue.discard(failed.first().id)
```

Build a "Failed uploads" or "Sync issues" screen from `peekAll()`.

---

[← Back to docs](README.md)
