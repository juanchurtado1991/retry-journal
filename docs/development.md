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
| Performance report | `./gradlew :retry-journal:performanceReport` | Nothing, ~1 minute | Real per-operation timing (JIT-warmed, median/p95 of hundreds of iterations) and memory (JOL) numbers for `DiskQueue` — not a test, a manual diagnostic. See [Performance report](#performance-report) below for the reference numbers and what they mean. |

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
- `pitestCore` and `performanceReport` — deliberately manual, see the table above.

### Performance report

`./gradlew :retry-journal:performanceReport` — real speed and memory numbers for `DiskQueue`, not
guesses. There's no JMH here: `me.champeau.jmh` applies the plain `java` Gradle plugin under the
hood, which is fundamentally incompatible with a module that's both Kotlin Multiplatform and an
Android library (verified — fails at configuration time). The timing harness in
[`PerformanceReport.kt`](../retry-journal/src/jvmTest/kotlin/com/retryjournal/tools/PerformanceReport.kt)
follows the same methodology by hand: a JIT warmup phase discarded before measuring, hundreds of
measured iterations, median/p95 instead of one sample so a GC pause or one slow write doesn't set
the headline number.

#### Speed

Single-operation cost (JIT-warmed, median of 500 iterations):

| Operation | Median | p95 |
|---|---|---|
| `enqueue()` | 351µs | 505µs |
| `peek()` | 100µs | 144µs |
| `get(id)` | 76µs | 91µs |
| `remove()` | 670µs | 861µs |

`remove()` costs roughly 2x `enqueue()` — it appends a tombstone record (cheap) but then also
bumps the on-disk generation counter, a second file write+flush that `enqueue()` doesn't pay.

`size()`/`isEmpty()` scale with backlog size (both scrub the live set for corrupt entries first —
`size()` does a second pass on top to count, `isEmpty()` doesn't, which is why it's consistently
about half the cost):

| Backlog size | `size()` median | `isEmpty()` median |
|---|---|---|
| 100 | 585µs | 301µs |
| 1,000 | 3.80ms | 1.92ms |
| 10,000 | 37.1ms | 18.2ms |

**A real finding from measuring end-to-end, not just single operations:** draining a queue by
hand through the public API (`while (!isEmpty()) { remove(peek().id) }`) is **not linear** —
`isEmpty()`'s scrub rescans the entire *remaining* live set on every single call, so a full drain
costs O(N²) in aggregate (594µs/entry at N=100 vs. 1.35ms/entry at N=1,000, still climbing).
`RetryJournalEngine.flush()` does **not** do this: it drives `HeadReplayExecutor`, which calls
`prepareHeadForReplay()`/`completeHeadReplay()` — those only scan forward from the head until they
find one readable entry, never the whole set. Measured separately to confirm before reporting
either number as "the real cost":

| Backlog size | Naive (`isEmpty`+`peek`+`remove`) per entry | Realistic (`flush()`'s actual path) per entry |
|---|---|---|
| 100 | 594µs | 698µs |
| 1,000 | 1.35ms (climbing) | 689µs |
| 10,000 | *not run — already proven non-linear* | 636µs |

The path `flush()` actually takes stays flat (~650-700µs/entry) regardless of backlog size — the
naive public-API pattern someone might reach for instead does not. If you're ever writing code
that manually drains a `DiskQueue` outside of `RetryJournalEngine`, prefer looping
`prepareHeadForReplay()`-style head-only access over `isEmpty()`+`peek()`+`remove()` at scale.

#### Memory

`DiskQueue` keeps [`LiveEntryIndex`](../retry-journal/src/commonMain/kotlin/com/retryjournal/queue/disk/LiveEntryIndex.kt)
(`liveOffsetsBySequence`) in memory for the lifetime of every open queue — one entry per request
still queued, mapping its sequence id to a packed disk offset/length. It's a dense `LongArray`-backed
index that exploits two things that are always true for how it's actually used (sequence ids are
assigned monotonically, and a legitimate packed value is never exactly `0L`) to store one raw
`long` per entry, no per-entry object at all — the sequence id is implicit in array position, so
there's nothing to box or store. Measured with [JOL](https://github.com/openjdk/jol) (Java Object
Layout) instead of guessing from nominal per-object sizes — roughly **90% less memory** than the
`LinkedHashMap<Long, Long>` this replaced.

The array can carry some growth slack, so the real number depends on *how* it got filled:

- **Recovery / compaction** (`replaceAllWith`) already knows the exact live count up front, so the
  backing array is presized exactly to it — no growth slack at all, landing right on the 8
  bytes/entry floor (one raw `long`, nothing else).
- **Organic growth** (`enqueue()` calling `set()` one at a time, with no upfront size known) grows
  the array 1.5x whenever it runs out of room, so it can carry up to ~1.5x slack right after a
  growth event, settling back down as `compactLeadingDeadSpace()` reclaims space during ordinary
  head-removal churn.

Last measured (JVM, your numbers may vary slightly by JDK/architecture):

| Backlog size | Via `replaceAllWith` (recovery/compaction) | Via sequential `set()` (organic growth) |
|---|---|---|
| 100 | 856 bytes (8.0 bytes/entry) | 1,136 bytes (10.2 bytes/entry) |
| 1,000 | 8,056 bytes (8.0 bytes/entry) | 8,224 bytes (7.9 bytes/entry) |
| 10,000 | 80,056 bytes (8.0 bytes/entry) | 93,040 bytes (9.4 bytes/entry) |
| 50,000 | 400,056 bytes (8.0 bytes/entry) | 470,768 bytes (9.4 bytes/entry) |
| 100,000 | 800,056 bytes (8.0 bytes/entry) | 1,059,152 bytes (11.8 bytes/entry) |

So a queue that reopens after a crash with 100,000 entries still pending loads its index at
**~800KB, not ~1MB** — the realistic worst case (a real crash-recovery scan, or a compaction pass)
lands right on the theoretical floor. A queue that grows to 100,000 organically via one `enqueue()`
call at a time can carry a bit more slack depending on exactly where its last growth event landed
relative to that particular backlog size — still ~9.3x smaller than the old `LinkedHashMap`, just
not perfectly flat.

**A full successful `flush()` does remove every delivered/dead-lettered entry from the index** —
but the backing array never shrinks back down on removal; only growth (and `replaceAllWith`, which
always reallocates to the true current span) ever resizes it. So between opens/compactions, the
footprint after a full drain reflects the **peak** backlog size the index reached since the last
`replaceAllWith`, not the current one — e.g. a queue that organically grew to 100,000 and later
drained to zero still holds onto that ~1MB array until the next reopen or compaction reallocates
it down, or the `DiskQueue` instance itself is garbage collected.

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
