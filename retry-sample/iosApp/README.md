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

## Open the Xcode project

A working `iosApp.xcodeproj` is checked into this folder — open it directly in Xcode, select a Simulator or device, and build & run. It already links the Kotlin framework via the standard KMP **Embed & Sign** (`embedAndSignAppleFrameworkForXcode`) build phase, targets `:retry-sample:composeApp:iosArm64` / `iosSimulatorArm64`, and has `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers` set from `Info.plist.snippet.xml`.

Starting a new project from scratch instead (e.g. embedding this sample in your own app)? Copy `AppDelegate.swift`, `ContentView.swift`, and merge `Info.plist.snippet.xml` into your own `Info.plist`, then repeat the framework-linking steps above by hand.

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
