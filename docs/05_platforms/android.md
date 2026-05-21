# Android

Secondary platform. Target: Phase 2 ship.

## Targets

- **minSdk:** 26 (Android 8.0 Oreo) — ~98 % of active devices in 2026
- **targetSdk:** latest stable (35+ in 2026)
- **compileSdk:** same as target
- **NDK:** if native code is needed (Opus, libwebrtc) — Phase 3+

## Key platform integrations

| Capability | Implementation |
|---|---|
| Secure storage | `EncryptedSharedPreferences` (Jetpack Security) + Android Keystore master key |
| Notifications | `NotificationManagerCompat` with channels per importance |
| Background work | Foreground service to maintain gateway connection (microphone permission for voice, Phase 3) |
| File picker | Storage Access Framework (`ACTION_OPEN_DOCUMENT`) |
| Audio capture | `AudioRecord` / Oboe (Phase 3) |
| Push notifications | FCM (Firebase Cloud Messaging) — **NOT for Phase 2**, gateway works when app is running |

## Process / lifecycle

Discord gateway = persistent WebSocket. Android aggressively kills background apps. Strategy:
- **Foreground service** with a persistent "Puklic running" notification when the app is in the background
- User toggle "Background mode" in Settings (default: on)
- Without foreground service: app suspends → gateway disconnects → reconnects on return

## Permissions

| Permission | Purpose | Phase |
|---|---|---|
| `INTERNET` | Network | 2 |
| `ACCESS_NETWORK_STATE` | Connectivity changes | 2 |
| `POST_NOTIFICATIONS` | Notifications (Android 13+) | 2 |
| `FOREGROUND_SERVICE` | Persistent gateway | 2 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Voice (Android 14+) | 3 |
| `RECORD_AUDIO` | Voice | 3 |
| `MODIFY_AUDIO_SETTINGS` | Voice | 3 |

## UI considerations

- Compose Multiplatform Android UI has full feature parity with Compose Android
- Material 3 as base theme
- Edge-to-edge support (transparent system bars)
- Predictive back gesture (Android 14+) — Compose Navigation hookup
- Dynamic color (Material You) — opt-in in Settings

## Distribution

- **Google Play:** primary
- **F-Droid:** secondary (reproducible build requirement — address in Phase 5)
- **APK direct:** always available on GitHub Releases

Discord ToS violation risk is higher on Google Play — Play may remove the app if Discord files a DMCA / ToS complaint. Plan B: F-Droid + APK distribution. README must state this explicitly.

## Foreground service notification

```kotlin
NotificationChannel("puklic.gateway", "Puklic background", NotificationManager.IMPORTANCE_LOW).apply {
    description = "Keeps Puklic connected to Discord while in the background"
    setShowBadge(false)
    setSound(null, null)
}
```

User-facing text: "Puklic is connected to Discord" + tap action returns to app.

## Differences from Desktop

- No tray, persistent notification instead
- Menu structure different (drawer + bottom tabs vs split view)
- File dialogs via Storage Access Framework, not JFileChooser
- Window resizing N/A (fixed screen) — except for tablets and foldables
- Mobile-first UX adaptations assumed (larger tap targets, gesture navigation)

## Adaptive layouts

Compose Multiplatform Material 3 adaptive:
- Compact (phone portrait): drawer + single pane
- Medium (tablet portrait, phone landscape): rail + two pane (channels | messages)
- Expanded (tablet landscape): permanent drawer + three pane (guilds | channels | messages)

The same adaptive system is then usable on Desktop (window resize) and iPad.

## Open questions

- **Push:** Discord has no public push API for user accounts. Option: custom notification relay (server-side component) — out of scope.
- **Voice on mobile (Phase 3):** battery impact — aggressive backoff, suspend non-active channels.
- **Storage limit:** mobile has less space — default cache 200 MB instead of 500.
