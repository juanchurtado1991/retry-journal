# iOS sample — setup guide

Run the same **offline queue → sync when online** demo on iPhone or Simulator. You need **macOS + Xcode** for this step — Kotlin/Native iOS targets don't build on Linux.

---

## What you'll get

Same Compose UI as desktop/Android:

1. Turn off network (or stop the chaos server) → send requests → they **queue on disk**
2. Restore network → **Sync now** (or background worker) → `flush()` delivers them

The shared Kotlin code (`:retry-sample:composeApp`) is already iOS-ready; this folder adds the **Xcode shell** to run it on device.

---

## Before you start

1. **Chaos server** (same as Android):

   ```bash
   ./gradlew :retry-sample:server:run
   ```

2. **Network host**
   - **Simulator:** `localhost` / Mac LAN IP works — simulator shares the Mac network.
   - **Physical iPhone:** set your Mac's LAN IP in `AppConstants.SERVER_HOST` (`retry-sample/composeApp/src/commonMain/.../AppConstants.kt`). Do **not** use `10.0.2.2` (Android emulator only).

---

## Create the Xcode project (one-time)

This repo ships **Swift reference files**, not a checked-in `.xbxproj` (can't be validated without Xcode).

1. In Xcode, create a **Multiplatform App** / **iOS App** named `iosApp` under `retry-sample/`, **or** use the Compose Multiplatform template's `iosApp/` output.
2. Copy from this folder:
   - `AppDelegate.swift`
   - `ContentView.swift`
   - Merge `Info.plist.snippet.xml` into your `Info.plist` (`BGTaskSchedulerPermittedIdentifiers`)
3. Link the Kotlin framework from `:retry-sample:composeApp:iosArm64` / `iosSimulatorArm64` (standard KMP **Embed & Sign** / `embedAndSignAppleFrameworkForXcode` Gradle task).
4. Build & run on Simulator or device.

Files here:

| File | Purpose |
|---|---|
| `AppDelegate.swift` | Calls `RetryJournalBackgroundSetup.shared.register()` (`:retry-worker`'s `BGTaskScheduler` registration) |
| `ContentView.swift` | SwiftUI wrapper around `MainViewController()` |
| `Info.plist.snippet.xml` | Background sync identifier — must match `AppConstants.IOS_BACKGROUND_TASK_ID` |

---

## Demo script (verify retry-journal on iOS)

Use this checklist after the app launches:

- [ ] **Airplane mode ON** (or server stopped) → tap **Upload a file** or stress test → pending count increases; Pending subtitle shows **Head: awaiting replay**
- [ ] **Airplane mode OFF** (server running) → **Sync now** → pending clears, log shows deliveries
- [ ] Optional: after a simulated crash mid-delivery, **Head: finishing local removal** appears until next flush
- [ ] Optional: **400 chaos** request lands in dead letter — same as desktop demo

Background sync: `AppDelegate` registers `BGTaskScheduler`; production apps rely on this so users don't tap Sync manually.

---

## More context

- Full sample walkthrough: [../README.md](../README.md)
- Library integration (mountain / offline UX, `flush()` scheduling): [../../README.md](../../README.md)
- iOS library verification debt (publish, CI): [../../ios_techdebt.md](../../ios_techdebt.md) — **1.0.0**, handoff desde Linux
