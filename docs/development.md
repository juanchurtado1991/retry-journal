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
- `pitestCore` — deliberately manual, see the table above.

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
