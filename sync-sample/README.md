# Ghost Sync — full-cycle sample

Demonstrates `:ghost-sync` end to end: a chaotic Ktor server, and a Compose Multiplatform app that
enqueues thousands of mutations offline and flushes them once connectivity returns.

| Module | What it is | Verified how |
|---|---|---|
| `shared` | `@GhostSerialization` models shared by server and app | Compiles (JVM + Android); KSP generates both serializers |
| `server` | Chaotic Ktor/CIO server | **Actually run** and hit with 22 real HTTP requests — see its own module for details |
| `composeApp` (Android) | Compose UI + kmpworkmanager integration + Ktorfit | Compiles; a real debug APK was built (`./gradlew :sync-sample:composeApp:assembleDebug`) |
| `composeApp` (iOS) | Same commonMain UI, Darwin engine, `IosWorker` | **Not compiled** — this machine has no Xcode. Kotlin/Native can't build Apple targets off macOS; Gradle just skips them (`kotlin.native.ignoreDisabledTargets=true`), same as `ghost-serializer`'s own CI on Linux |
| `iosApp/` | Xcode host project | Reference Swift files only, not a real `.pbxproj` — see `iosApp/README.md` |

## Run the chaos server

```bash
./gradlew :sync-sample:server:run
```

Listens on `:8080`. `GET /health` → `200 ok`. `POST /mutations` behaves badly on a rotation: every
5th request is slow-but-succeeds, every 7th returns 503, every 13th returns 400, every 20th stalls
15s (long enough to blow past the sample client's 6s socket timeout). Confirmed by hand: 22
sequential requests returned exactly the expected pattern (200×18, 503×3, 400×1, hitting request
numbers 7, 13, 14, 21).

## Run the Android app

Point `AppConstants.SERVER_HOST` at wherever the server is reachable from the device:
`10.0.2.2` (already set) is the Android **emulator's** alias for the host machine; on a physical
device use your machine's LAN IP instead.

```bash
./gradlew :sync-sample:composeApp:installDebug
```

On the "Stress test" screen: tap **Enqueue offline** *before* starting the server (or with the
device in airplane mode) to exercise `GhostOfflineQueuePlugin` — every POST fails with a real
connection error and lands in the `DiskQueue`. Start the server, tap **Flush now** to drain it
through `GhostSyncEngine`; the periodic `kmpworkmanager` sync (every 15 min) does the same thing
in the background without any button press.

The **Ktorfit** button does the same offline-queueing/flush dance through a Ktorfit-generated
`MutationApi` interface instead of a hand-written `HttpClient.post()` call — proof that
`GhostOfflineQueuePlugin` intercepts transparently no matter how the request was built, since
Ktorfit's generated code is still just calling the same `HttpClient` under the hood. See
`SyncSetup.kt` and `MutationApi.kt`.

### Ktorfit + Ktor 2.3.11 version note

Ktorfit's current releases default to Ktor 3.x, which would conflict with `ghost-ktor`'s pinned
Ktor 2.3.11 in the same module. `2.1.0` is the last Ktorfit release that defaults to Ktor 2.x
(2.3.12, compatible enough with 2.3.11) — confirmed via its changelog, not assumed. Its Gradle
plugin's automatic KSP-processor-version resolution doesn't know about this project's exact
Kotlin/KSP combo (2.1.10 / `1.0.31`) and tries to fetch a `ktorfit-ksp` artifact that doesn't
exist, so the Gradle plugin (`de.jensklingenberg.ktorfit`) isn't applied here at all — instead
`ktorfit-ksp:2.1.0-1.0.27` (the closest version actually published to Maven Central, checked via
`maven-metadata.xml`) is wired in by hand via `add("kspCommonMainMetadata"/"kspAndroid"/..., ...)`,
same pattern as `kmpworkmanager`. `MutationApi` lives in `commonMain`, which additionally requires
`kspCommonMainMetadata` (not just the per-target `ksp<Target>` configs) plus registering
`build/generated/ksp/metadata/commonMain/kotlin` as a `commonMain` source root — confirmed by
actually finding zero generated output until both were added, then finding
`_MutationApiImpl.kt` once they were.

## Run the iOS app

See `iosApp/README.md` — needs an actual Xcode project this session couldn't create or verify.

## What's verified vs. best-effort in this module

Everything in `shared` and `server` was compiled and actually run here. In `composeApp`, the
`kmpworkmanager` integration (`KmpWorkManagerSetup.android.kt`, `SyncWorkerAndroid.kt`,
`GhostSyncWorker.kt`) was **not** just copied from the library's README — its public README omits
the real package (`dev.brewkits.kmpworkmanager.background.domain`), the real scheduler accessor
(`KmpWorkManager.getInstance().backgroundTaskScheduler`, not a bare `.scheduler`), the real
generated factory class name (`AndroidWorkerFactoryGenerated`, confirmed by actually running KSP
and reading its output), and a required `reason: String` parameter on `WorkerResult.Retry` the
README's own snippet omits. All of that was corrected by decompiling the resolved
`kmpworkmanager-android-3.0.1` artifact and cross-checking against a real, successful
`:sync-sample:composeApp:assembleDebug` build that produced an installable APK.

The `IosWorker` side (`SyncWorkerIos.kt`, `PlatformHttpClientEngine.ios.kt`,
`PlatformDataDirectory.ios.kt`) follows the same package/API conventions by analogy but was never
compiled — there is no Kotlin/Native Apple toolchain on this machine to verify it against.
