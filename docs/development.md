# Building, testing & publishing

```bash
./gradlew ciTestJvm ciCompile     # Linux CI parity
./gradlew :retry-journal:jvmTest  # library unit tests only
./gradlew ciCoverage              # Kover gate (≥90% JVM)
```

## Contributing

Most of `:retry-journal`'s test suite lives in `commonTest` and runs natively on all three
platforms from the same source — a test you add there is automatically JVM + Android + iOS
coverage, not just JVM. A few tests are platform-specific by necessity (see the "JVM-only" /
"Android-only" rows below) and stay in `jvmTest` / `androidInstrumentedTest` instead.

| What | Command | Requires | Covers |
|---|---|---|---|
| JVM unit tests | `./gradlew :retry-journal:jvmTest` | Nothing | The full `commonTest` + `jvmTest` suite — `DiskQueue`, `RetryJournalEngine`, dead letters, the record fuzz/load tests, the cross-process lock test |
| Android unit tests | `./gradlew :retry-journal:testDebugUnitTest` | Nothing (runs on the JVM, no emulator) | Same `commonTest` suite, compiled and run for the Android target |
| iOS Simulator tests | `./gradlew :retry-journal:iosSimulatorArm64Test` | macOS + Xcode | Same `commonTest` suite, compiled and run as a native binary in the Simulator |
| Android instrumented tests | `./gradlew :retry-journal:connectedDebugAndroidTest` | A running Android emulator or device (`adb devices` shows one) | `DiskQueue` against a **real** `Context`/on-device filesystem/ART runtime — `testDebugUnitTest` alone only proves it works on a plain JVM with a stub `android.jar` |
| Everything above except iOS/instrumented | `./gradlew ciTestJvm` | Nothing | What CI's `linux` job runs on every push |
| Coverage gate | `./gradlew ciCoverage` | Nothing | Kover line coverage on `commonMain`+`jvmMain`, ≥90% or the build fails |
| Mutation testing | `./gradlew :retry-journal:pitestCore` | Nothing, but ~10 minutes | Whether the test suite's assertions are actually strong enough to catch a bug in `DiskQueue`/`RetryJournalEngine`/`DeadLetterQueue`/the record codecs, not just whether they execute the line. Manual/occasional — too slow for a per-commit gate. HTML report: `retry-journal/build/reports/pitest/index.html` |
| Binary compatibility | `./gradlew :retry-journal:apiCheck :retry-worker:apiCheck` | macOS + Xcode for the full check (JVM/Android-only: `jvmApiCheck androidApiCheck`, no Mac needed) | Whether this change accidentally removed/changed a public API member. Fails with an exact diff if so. If the change is a deliberate, intentional API change, run `apiDump` instead of `apiCheck` to update the committed baseline under `api/` |
| Memory probe | `./gradlew :retry-journal:memoryProbe` | Nothing | Real (JOL-measured) retained memory of `DiskQueue`'s in-memory live-entry index at increasing backlog sizes — not a test, a manual diagnostic. See [Memory probe](#memory-probe) below for the reference numbers and what they mean. |

Full local pass before opening a PR:

```bash
./gradlew ciTestJvm ciCoverage ciCompile
```

Add `:retry-journal:iosSimulatorArm64Test`, `:retry-journal:connectedDebugAndroidTest`, and
`:retry-journal:klibApiCheck` on top of that if you touched anything in `:retry-journal` and have
a Mac / a booted emulator handy — CI runs the iOS test and `klibApiCheck` for you on every push
(they're in the macOS-only `ios` job), but not the Android instrumented one (see below).

If you intentionally changed a public API and `apiCheck` is now failing on purpose, run
`./gradlew :retry-journal:apiDump :retry-worker:apiDump` to update the committed baseline, then
commit the changed files under `api/`.

**Not yet wired into CI:**
- `connectedDebugAndroidTest` — needs a running emulator; GitHub Actions doesn't have one attached
  by default. Run it locally before a PR that touches `:retry-journal`.
- `pitestCore` and `memoryProbe` — deliberately manual, see the table above.

### Memory probe

`DiskQueue` keeps a `LinkedHashMap<Long, Long>` (`liveOffsetsBySequence`) in memory for the
lifetime of every open queue — one entry per request still queued, mapping its sequence id to a
packed disk offset/length. `./gradlew :retry-journal:memoryProbe` measures its *actual* retained
size with [JOL](https://github.com/openjdk/jol) (Java Object Layout) instead of guessing from
nominal per-object sizes, and compares it against a flat `LongArray` — the shape a
zero-allocation redesign would use — as a reference point.

Last measured (JVM, your numbers may vary slightly by JDK/architecture):

| Backlog size | `LinkedHashMap<Long,Long>` (current) | `LongArray` (dense, no stored key) | Ratio |
|---|---|---|---|
| 100 | 9,904 bytes | 816 bytes | 12.1x |
| 1,000 | 96,272 bytes | 8,016 bytes | 12.0x |
| 10,000 | 945,616 bytes | 80,016 bytes | 11.8x |
| 50,000 | 4,924,368 bytes | 400,016 bytes | 12.3x |
| 100,000 | 9,848,656 bytes | 800,016 bytes | 12.3x |

Marginal cost per entry converges to ~98 bytes in the current map (`Entry` object + two boxed
`Long`s + bucket-array share) vs. exactly 8 bytes in a dense array (no per-entry object at all —
the sequence id is implicit in array position, so there's nothing to box or store).

**A full successful `flush()` does remove every delivered/dead-lettered entry from the map** —
but removing entries from a `HashMap`/`LinkedHashMap` never shrinks its backing bucket array back
down; only growth ever resizes it. So the *objects* (bookkeeping `Entry`s and boxed `Long`s) for
drained entries are freed and eligible for GC, but the map's footprint afterward reflects the
**peak** backlog size it ever reached, not the current one, for the rest of that `DiskQueue`
instance's lifetime:

| Peak backlog reached | Bytes at peak | Bytes after draining to empty | Bytes for a queue that never grew |
|---|---|---|---|
| 1,000 | 96,272 | 8,272 | 64 |
| 10,000 | 945,616 | 65,616 | 64 |
| 100,000 | 9,848,656 | 1,048,656 | 64 |

In other words: a queue that once backed up to 100,000 entries and later drained to zero still
holds onto ~1MB for its bucket array alone, indefinitely, until the `DiskQueue` instance itself is
garbage collected. A `LongArray`-based redesign would have the same never-shrinks-on-removal
property, but at ~8 bytes/entry instead of ~98, the peak-size tax is over an order of magnitude
smaller.

Try the demo:

```bash
./gradlew :retry-sample:composeApp:run
```

## Publishing

Sonatype + GPG in `~/.gradle/gradle.properties`, then:

```bash
./gradlew publishToMavenCentral
```

## More

- Change history: [CHANGELOG.md](../CHANGELOG.md)

---

[← Back to docs](README.md)
