# 👻 Ghost Sync — Sample App

A Compose Multiplatform demo that shows `ghost-sync` in action. Turn the server off, make some requests, watch them queue up locally — then turn it back on, hit Sync, and watch them deliver in real time.

---

## 🚀 Run in 60 Seconds (No Device Needed)

The desktop build includes the server built in. Just run:

```bash
./gradlew :sync-sample:composeApp:run
```

A window opens with the server already running. That's it!

---

## 🖥️ What You'll See

![Demo screen showing pending requests, server toggle, sync button and activity log](docs/demo-desktop.png)

The UI shows:
- **Server On/Off toggle** — simulate going offline instantly.
- **Pending requests** — one dot per queued request waiting to be sent.
- **Activity log** — a live feed of everything happening.

---

## 🎮 Try This Flow

1. **Flip the server OFF** (the dot turns red).
2. **Tap "Upload a file"** — it can't reach the server, so it gets saved to disk automatically.
3. **Flip the server back ON**.
4. **Tap "Sync now"** — watch the request deliver live and the chip turn green and disappear.

> 💡 **"Send 5 JSON requests"** does the same thing with plain JSON mutations instead of a file upload.

---

## ❓ Why Doesn't "Sync now" Empty the Queue in One Tap?

This is intentional — not a bug. The demo uses a **chaos server** that deliberately misbehaves on a rotation:

| Every Nth request | Behavior |
|---|---|
| 5th | Slow, but succeeds |
| 7th | Returns `503 Service Unavailable` |
| 13th | Returns `400 Bad Request` |
| 20th | Stalls 15 seconds (triggers a timeout) |

When a timeout or `5xx` hits, `flush()` **stops immediately** instead of skipping over it — just like a real offline scenario. Tap **Sync now** again to continue from where it left off.

> In a real app, a background scheduler calls `flush()` automatically on a timer, so the user never even notices.

---

## 📱 Run on Android

Android can't embed the server in-process, so you run it separately:

```bash
# Terminal 1 — start the chaos server
./gradlew :sync-sample:server:run

# Terminal 2 — install the app
./gradlew :sync-sample:composeApp:installDebug
```

- **Emulator**: already points to `10.0.2.2` (host machine's localhost) — no changes needed.
- **Physical device**: update `PlatformServerHost.android.kt` to your machine's LAN IP.

---

## 🍎 Run on iOS

See [`iosApp/README.md`](iosApp/README.md).

> iOS builds require macOS + Xcode. The shared Kotlin code compiles fine on Linux/Windows; only the final Xcode link step needs macOS.

---

## ✅ Build Status

| Target | Status |
|---|---|
| `shared` (JVM + Android) | ✅ Compiles, KSP serializers generated |
| `server` | ✅ Runs live, chaos rotation verified |
| `composeApp` (Desktop) | ✅ Runs via `./gradlew :sync-sample:composeApp:run` |
| `composeApp` (Android) | ✅ Compiles, debug APK builds |
| `composeApp` (iOS) | ⏭️ Skipped on non-macOS (no Xcode) |
| `iosApp/` | 📄 Reference Swift scaffold only |
