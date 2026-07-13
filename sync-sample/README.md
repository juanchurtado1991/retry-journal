# Ghost Sync — full-cycle sample

A Compose Multiplatform app that shows `:ghost-sync` actually working: turn the server off, do
things, watch requests queue up locally; turn it back on, hit sync, watch them drain out.

## Try it in 60 seconds

No device, no emulator, no second terminal — the desktop build embeds the chaos server in the
same process:

```bash
./gradlew :sync-sample:composeApp:run
```

A window opens with the server already on. That's it.

### What you'll see

![The demo screen: a server on/off switch, live pending/dead-letter counts, one chip per pending request, an Upload a file / Sync now pair of buttons, and a running activity log](docs/demo-desktop.png)

This particular run has 9,994 requests pending — the row of gray dots is
[`QueueVisualization`](composeApp/src/commonMain/kotlin/com/ghostserializer/sync/sample/app/App.kt):
one chip per pending request, capped at 20 on screen at once (a stress test does not need — or
want — 10,000 individual chips). Each chip flashes green (delivered) or red (dead-lettered) as
**Sync now** actually resolves it, driven by real progress from `GhostSyncEngine`, not a simulated
animation.

Try this flow yourself:

1. Flip the switch off. The dot turns red.
2. Tap **Upload a file** and pick anything from disk. It fails to send (no server) and shows up
   as a chip under "Pending" instead — that's `GhostOfflineQueuePlugin` catching the connection
   failure and persisting the file to `DiskQueue`, byte for byte.
3. Flip the switch back on.
4. Tap **Sync now**. Watch the chip flash green and disappear — that's `GhostSyncEngine.flush()`
   replaying the queued request for real, reported back live through `FlushProgress`.

Everything below "Show advanced options" does the same thing at a larger scale — a "Send 5" for a
handful of plain JSON mutations, two stress-test buttons (1,000 / 10,000) to check throughput, and
a Ktorfit-generated call to prove the interceptor works no matter how a request was built.

### Why "Sync now" might not finish a big queue in one click

This is expected, not a bug: after a 10,000-entry stress test, tapping **Sync now** once will
usually *not* drain the whole queue. `GhostSyncEngine.flush()` replays entries one at a time
against the real chaos server (see the rotation below) — every 20th request stalls 15 seconds,
longer than the client's 6-second socket timeout, which throws a genuine `IOException`. `flush()`
treats that exactly like the server going down mid-sync: it **stops immediately**, leaving
everything still queued for the next attempt, instead of skipping the failure and pressing on.

With ~10,000 entries that stall is going to happen roughly every 20 requests — so one `flush()`
call only ever gets partway through before stopping, and **Sync now** needs to be tapped again to
pick up where it left off. This is `GhostSyncEngine.flush()`'s documented behavior (see the root
README's "How it works" table: `5xx or IOException` → *"stop here, the rest waits for the next
`flush()`"*) — the demo's button just makes one `flush()` call per tap, it doesn't loop. A real
background sync job calling `flush()` on a timer would simply pick the rest up on its next run
without anyone noticing.

## Run on Android

Android/iOS have no in-process server story, so you run the chaos server yourself:

```bash
./gradlew :sync-sample:server:run          # in one terminal
./gradlew :sync-sample:composeApp:installDebug   # in another
```

`PlatformServerHost.android.kt` already points at `10.0.2.2` — the emulator's alias for the host
machine's `localhost`. On a physical device, change it to your machine's LAN IP instead. The demo
screen looks and behaves the same as on desktop, minus the server toggle (there's nothing local to
switch — a hint on screen explains this).

The chaos server itself: `GET /health` → `200 ok`. `POST /mutations` behaves badly on a rotation —
every 5th request is slow-but-succeeds, every 7th returns 503, every 13th returns 400, every 20th
stalls 15s (long enough to blow past the client's 6s socket timeout and force an offline queue).
`POST /uploads` is simpler — it always succeeds when reachable, since the point of that endpoint is
proving a real multipart body survives the queue, not exercising the chaos rotation again.

## Run on iOS

See [`iosApp/README.md`](iosApp/README.md) — the Swift side is a reference scaffold, not a real
buildable Xcode project (this environment has no macOS/Xcode to generate or verify one).

## What's actually running here

| Target | Status |
|---|---|
| `shared` | Compiles (JVM + Android); KSP generates both serializers |
| `server` | Runs for real — hit with real HTTP requests, chaos rotation confirmed by hand |
| `composeApp` (Desktop/JVM) | Runs for real — `./gradlew :sync-sample:composeApp:run`, embedded server binds the port, window opens, no exceptions |
| `composeApp` (Android) | Compiles; a real debug APK builds (`assembleDebug`) |
| `composeApp` (iOS) | **Not compiled** — no macOS/Xcode here. Gradle skips it (`kotlin.native.ignoreDisabledTargets=true`), same as `ghost-serializer`'s own CI on Linux |
| `iosApp/` | Reference Swift files only |

---

## Implementation notes

Deeper detail on decisions that took real investigation to get right — useful if you're extending
this sample, not necessary just to run it.

### Ktorfit pinned to 2.1.0, wired by hand

Ktorfit's current releases default to Ktor 3.x, which conflicts with `ghost-ktor`'s pinned Ktor
2.3.11 in the same module. `2.1.0` is the last Ktorfit release that defaults to Ktor 2.x (2.3.12,
compatible enough with 2.3.11) — confirmed via its changelog, not assumed. Its Gradle plugin's
automatic KSP-version resolution doesn't know this project's exact Kotlin/KSP combo (2.1.10 /
`1.0.31`) and tries to fetch a `ktorfit-ksp` artifact that doesn't exist, so the Gradle plugin
(`de.jensklingenberg.ktorfit`) isn't applied at all — `ktorfit-ksp:2.1.0-1.0.27` (the closest
version actually published, checked via `maven-metadata.xml`) is wired in by hand instead, same
pattern as `kmpworkmanager` below. `MutationApi` lives in `commonMain`, which needs
`kspCommonMainMetadata` (not just the per-target `ksp<Target>` configs) plus registering
`build/generated/ksp/metadata/commonMain/kotlin` as a `commonMain` source root — confirmed by
finding zero generated output until both were added.

### kmpworkmanager's real API vs. its README

The `kmpworkmanager` integration (`KmpWorkManagerSetup.android.kt`, `SyncWorkerAndroid.kt`,
`GhostSyncWorker.kt`) was corrected by decompiling the resolved `kmpworkmanager-android-3.0.1`
artifact, not copied from its public README, which omits: the real package
(`dev.brewkits.kmpworkmanager.background.domain`), the real scheduler accessor
(`KmpWorkManager.getInstance().backgroundTaskScheduler`, not a bare `.scheduler`), the real
generated factory class name (`AndroidWorkerFactoryGenerated`), and a required `reason: String`
parameter on `WorkerResult.Retry` its own snippet omits. All confirmed against a real, successful
`assembleDebug` build that produced an installable APK. `kmpworkmanager` publishes Android + iOS
only — no JVM — so it (and `GhostSyncWorker`) live in a `mobileMain` intermediate source set that
`desktopMain` doesn't depend on. The desktop build has no periodic background sync as a result, but
**Sync now** works identically everywhere since it never depended on a scheduler in the first
place — see the root README's "Scheduling: any of them, or none".

The `IosWorker` side follows the same package/API conventions by analogy but was never compiled —
no Kotlin/Native Apple toolchain on this machine to verify it against.
