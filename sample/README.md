# Ghost Sync — full-cycle sample (Fase 6)

Demonstrates `:ghost-sync` end to end: a chaotic Ktor server, and a Compose Multiplatform app that
enqueues thousands of mutations offline and flushes them once connectivity returns.

| Module | What it is | Verified how |
|---|---|---|
| `shared` | `@GhostSerialization` models shared by server and app | Compiles (JVM + Android); KSP generates both serializers |
| `server` | Chaotic Ktor/CIO server | **Actually run** and hit with 22 real HTTP requests — see its own module for details |
| `composeApp` (Android) | Compose UI + kmpworkmanager integration | Compiles; a real debug APK was built (`./gradlew :sample:composeApp:assembleDebug`) |
| `composeApp` (iOS) | Same commonMain UI, Darwin engine, `IosWorker` | **Not compiled** — this machine has no Xcode. Kotlin/Native can't build Apple targets off macOS; Gradle just skips them (`kotlin.native.ignoreDisabledTargets=true`), same as `ghost-serializer`'s own CI on Linux |
| `iosApp/` | Xcode host project | Reference Swift files only, not a real `.pbxproj` — see `iosApp/README.md` |

## Run the chaos server

```bash
./gradlew :sample:server:run
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
./gradlew :sample:composeApp:installDebug
```

On the "Stress test" screen: tap **Enqueue offline** *before* starting the server (or with the
device in airplane mode) to exercise `GhostOfflineQueuePlugin` — every POST fails with a real
connection error and lands in the `DiskQueue`. Start the server, tap **Flush now** to drain it
through `GhostSyncEngine`; the periodic `kmpworkmanager` sync (every 15 min) does the same thing
in the background without any button press.

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
`:sample:composeApp:assembleDebug` build that produced an installable APK.

The `IosWorker` side (`SyncWorkerIos.kt`, `PlatformHttpClientEngine.ios.kt`,
`PlatformDataDirectory.ios.kt`) follows the same package/API conventions by analogy but was never
compiled — there is no Kotlin/Native Apple toolchain on this machine to verify it against.
