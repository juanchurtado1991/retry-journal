# iOS sample — setup guide

Run the same **offline queue → sync when online** demo on iPhone or Simulator. You need **macOS + Xcode** for this step — Kotlin/Native iOS targets don't build on Linux.

---

## What you'll get

Same Compose UI as desktop/Android:

1. Turn off network (or stop the chaos server) → send requests → they **queue on disk**
2. Restore network → **Sync now** (or background worker) → `flush()` delivers them

The shared Kotlin code (`:sync-sample:composeApp`) is already iOS-ready; this folder adds the **Xcode shell** to run it on device.

---

## Before you start

1. **Chaos server** (same as Android):

   ```bash
   ./gradlew :sync-sample:server:run
   ```

2. **Network host**
   - **Simulator:** `localhost` / Mac LAN IP works — simulator shares the Mac network.
   - **Physical iPhone:** set your Mac's LAN IP in `AppConstants.SERVER_HOST` (`sync-sample/composeApp/src/commonMain/.../AppConstants.kt`). Do **not** use `10.0.2.2` (Android emulator only).

---

## Create the Xcode project (one-time)

This repo ships **Swift reference files**, not a checked-in `.xbxproj` (can't be validated without Xcode).

1. In Xcode, create a **Multiplatform App** / **iOS App** named `iosApp` under `sync-sample/`, **or** use the Compose Multiplatform template's `iosApp/` output.
2. Copy from this folder:
   - `AppDelegate.swift`
   - `ContentView.swift`
   - Merge `Info.plist.snippet.xml` into your `Info.plist` (`BGTaskSchedulerPermittedIdentifiers`)
3. Link the Kotlin framework from `:sync-sample:composeApp:iosArm64` / `iosSimulatorArm64` (standard KMP **Embed & Sign** / `embedAndSignAppleFrameworkForXcode` Gradle task).
4. Build & run on Simulator or device.

Files here:

| File | Purpose |
|---|---|
| `AppDelegate.swift` | Koin + background task registration (kmpworkmanager) |
| `ContentView.swift` | SwiftUI wrapper around `MainViewController()` |
| `Info.plist.snippet.xml` | Background sync identifier — must match `SyncWorkerIos` |

If Koin APIs differ from your kmpworkmanager version, adjust `AppDelegate.swift` to match their docs.

---

## Demo script (verify ghost-sync on iOS)

Use this checklist after the app launches:

- [ ] **Airplane mode ON** (or server stopped) → tap **Upload a file** or stress test → pending count increases
- [ ] **Airplane mode OFF** (server running) → **Sync now** → pending clears, log shows deliveries
- [ ] Optional: **400 chaos** request lands in dead letter — same as desktop demo

Background sync: `AppDelegate` registers `BGTaskScheduler`; production apps rely on this so users don't tap Sync manually.

---

## More context

- Full sample walkthrough: [../README.md](../README.md)
- Library integration (mountain / offline UX, `flush()` scheduling): [../../README.md](../../README.md)
- iOS library verification debt (publish, CI): [../../ios_techdebt.md](../../ios_techdebt.md)
