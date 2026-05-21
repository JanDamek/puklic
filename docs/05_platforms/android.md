# Android

Sekundární platforma. Cíl: fáze 2 ship.

## Targets

- **minSdk:** 26 (Android 8.0 Oreo) — ~98 % aktivních zařízení v 2026
- **targetSdk:** latest stable (35+ v 2026)
- **compileSdk:** stejný jako target
- **NDK:** v případě potřeby native (Opus, libwebrtc) — fáze 3+

## Klíčové platform integrace

| Capability | Implementace |
|---|---|
| Secure storage | `EncryptedSharedPreferences` (Jetpack Security) + Android Keystore master key |
| Notifications | `NotificationManagerCompat` s channels per importance |
| Background work | Foreground service pro gateway connection udržení (microphone permission pro voice, fáze 3) |
| File picker | Storage Access Framework (`ACTION_OPEN_DOCUMENT`) |
| Audio capture | `AudioRecord` / Oboe (fáze 3) |
| Push notifications | FCM (Firebase Cloud Messaging) — **NE pro fáze 2**, gateway funguje při běžící app |

## Process / lifecycle

Discord gateway = persistent WebSocket. Android agresivně killuje background apps. Strategie:
- **Foreground service** s persistent notifikací „Puklic running" když je app v backgroundu
- User toggle „Background mode" v Settings (default: on)
- Bez foreground: app suspend → gateway disconnect → reconnect při návratu

## Permissions

| Permission | Účel | Phase |
|---|---|---|
| `INTERNET` | Network | 2 |
| `ACCESS_NETWORK_STATE` | Connectivity changes | 2 |
| `POST_NOTIFICATIONS` | Notifications (Android 13+) | 2 |
| `FOREGROUND_SERVICE` | Persistent gateway | 2 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Voice (Android 14+) | 3 |
| `RECORD_AUDIO` | Voice | 3 |
| `MODIFY_AUDIO_SETTINGS` | Voice | 3 |

## UI considerations

- Compose Multiplatform Android UI je full feature parity s Compose Android
- Material 3 jako base theme
- Edge-to-edge support (transparent system bars)
- Predictive back gesture (Android 14+) — Compose Navigation hookup
- Dynamic color (Material You) — opt-in v Settings

## Distribution

- **Google Play:** primary
- **F-Droid:** sekundární (reproducible build požadavek — řešit fáze 5)
- **APK direct:** vždy dostupné na GitHub Releases

Discord ToS violation risk je u Google Play vyšší — Play může app odstranit pokud Discord podá DMCA / ToS complaint. Plán B: F-Droid + APK distribution. README to musí explicit uvést.

## Foreground service notifikace

```kotlin
NotificationChannel("puklic.gateway", "Puklic background", NotificationManager.IMPORTANCE_LOW).apply {
    description = "Keeps Puklic connected to Discord while in the background"
    setShowBadge(false)
    setSound(null, null)
}
```

User-facing text: „Puklic je připojen k Discordu" + tap action vrátí do app.

## Specifika oproti Desktop

- Žádný tray, místo toho persistent notification
- Menu structure jiná (drawer + bottom tabs vs split view)
- File dialogs cez Storage Access Framework, ne JFileChooser
- Window resizing N/A (fixed screen) — kromě tabletů a foldablů
- Předpokládá se mobile-first UX adaptace (větší tap targets, gesture nav)

## Adaptive layouts

Compose Multiplatform Material 3 adaptive:
- Compact (phone portrait): drawer + single pane
- Medium (tablet portrait, phone landscape): rail + two pane (channels | messages)
- Expanded (tablet landscape): permanent drawer + three pane (guilds | channels | messages)

Stejný adaptive systém pak použitelný na Desktop (window resize) a iPad.

## Open questions

- **Push:** Discord nemá veřejné push API pro user accounts. Možnost: vlastní notification relay (server-side komponenta) — out of scope.
- **Voice na mobile (fáze 3):** battery impact — agresivní backoff, suspend non-active channels.
- **Storage limit:** mobile má méně místa — default cache 200 MB místo 500.
