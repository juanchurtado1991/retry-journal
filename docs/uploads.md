# File & image uploads offline

The plugin captures **whatever bytes** Ktor would have sent — including `MultiPartFormDataContent`:

```kotlin
retryJournal.client.post("https://api.example.com/upload") {
    setBody(
        MultiPartFormDataContent(
            formData {
                append("photo", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"trail.jpg\"")
                })
            }
        )
    )
}
```

Same `OfflineQueuedException` flow — when `flush()` runs on Wi‑Fi, the upload is replayed.

**Size limit:** each queued meta/body field defaults to **64 MiB** (`maxRecordFieldSize` in `RetryJournal.create`). Larger uploads fail closed with `BodyCaptureException` instead of silently truncating.

---

[← Back to docs](README.md)
