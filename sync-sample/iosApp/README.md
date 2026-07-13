# iosApp — setup notes

This folder is **not** a buildable Xcode project. It could not be created or verified on the
machine this repo was built on (Linux, no Xcode — Kotlin/Native cannot even compile the
`iosArm64`/`iosSimulatorArm64` targets of `:sync-sample:composeApp` on this host; Gradle just skips
them, the same way `ghost-serializer`'s own CI does on non-macOS runners). Hand-writing a
`.pbxproj` blind is far more likely to produce a project that silently doesn't open than to save
you time, so it wasn't attempted.

## To actually run the iOS sample

1. In Xcode (or Android Studio's KMP wizard), create a new **Multiplatform App** / **iOS App**
   project named `iosApp` inside `sync-sample/`, or use the standard Compose Multiplatform project
   template's `iosApp/` output — either gives you a real `.pbxproj`.
2. Copy `AppDelegate.swift`, `ContentView.swift`, and the `Info.plist` snippet below into it.
3. Add the Kotlin framework: in the Xcode project's build settings / Package Dependencies, link
   against the `ComposeApp` framework produced by `:sync-sample:composeApp:iosArm64` /
   `:sync-sample:composeApp:iosSimulatorArm64` (a "Kotlin Framework" build phase or
   `embedAndSignAppleFrameworkForXcode` Gradle task, same as any KMP+Compose project).
4. Set `AppConstants.SERVER_HOST` (in `sync-sample/composeApp/src/commonMain/.../AppConstants.kt`) to
   your Mac's LAN IP instead of `10.0.2.2` (that alias only resolves on the Android emulator) if
   testing against a physical device; `localhost` works for the iOS Simulator since it shares the
   host's network namespace.

## Files here

- `AppDelegate.swift` — Koin init + `BGTaskScheduler` registration, adapted from kmpworkmanager's
  own README. **Not independently verified** against the real Koin API surface on this machine
  (no Xcode) — cross-check `koin.getChainExecutor()`'s actual signature if it doesn't compile.
- `ContentView.swift` — the standard `UIViewControllerRepresentable` wrapper around
  `MainViewController()` (from `sync-sample/composeApp/src/iosMain/.../MainViewController.kt`).
- `Info.plist.snippet.xml` — the `BGTaskSchedulerPermittedIdentifiers` entry your real
  `Info.plist` needs; the id must match `SyncWorkerIos`'s `bgTaskId` and the identifier registered
  in `AppDelegate.swift`.

## Manual verification checklist (Fase 6, from the architecture plan)

This is the step that can't be automated from this session at all — it needs a real device or
simulator:

1. Run `:sync-sample:server:run` (chaos server) and the app side by side.
2. In the app, disable network (airplane mode) or don't start the server yet, then tap
   "Stress test: 10,000" to enqueue offline.
3. Re-enable network / start the server, tap "Flush now".
4. While step 3 runs, watch heap allocation in Xcode Instruments (iOS) or Android Studio Profiler
   (Android) — it should stay flat during the mass flush, confirming Ghost's zero-allocation
   serialization path holds under load. This was the original design goal and is the one thing in
   this whole plan that only a human with a device can actually confirm.
