# Android

Shipping platform (in scope since 2026-11-24): package `cz.damek.puklic.app`, company Google Play account, internal testing track first. The app is implemented independently of FP-14h (PUKLIC-123); the voice media engine (PUKLIC-124) is the shared DAVE-free `SharedVoiceClient`, see `docs/03_infrastructure/architect-reports/2026-11-25-android-voice-shared-orchestrator.md`.

## Targets

- **minSdk:** 26 (Android 8.0 Oreo) — ~98 % of active devices in 2026
- **targetSdk:** 36 (Play requirement)
- **compileSdk:** 37 (required by current AndroidX/Coil AAR metadata; checked by `checkReleaseAarMetadata`)
- **NDK:** if native code is needed (Opus, libwebrtc) — Phase 3+

## Key platform integrations

Actuals live in `:android:platform` (`dev.puklic.platform.android`); the wiring is `AndroidDependencyGraph` in `:android:app`, the manual-DI analogue of the desktop and iOS graphs.

| Capability | Implementation |
|---|---|
| Secure storage | `AndroidSecureStorage`: AES-256-GCM with a non-exportable Android Keystore key (`KeystoreAesGcm`), ciphertext in private `SharedPreferences`, entry key bound as AAD. `androidx.security:security-crypto` is not used (deprecated). Auto Backup is off (`allowBackup=false`) because Keystore keys are not restored elsewhere. An undecryptable entry is dropped and reads as absent, which sends the user to sign-in |
| Paths | `AndroidPlatformPaths`: `filesDir`, `cacheDir`, `filesDir/config`, `noBackupFilesDir/crashes`, `getDatabasePath("puklic.db")` |
| Database | `AndroidDriverFactory` (`AndroidSqliteDriver`, WAL + foreign keys + the same tuning pragmas as JVM/iOS) |
| Clipboard | `AndroidPlatformClipboard`: `ClipboardManager`; images via `FileProvider` content URI. Needed for pasting the Discord token |
| Open URL / file | `AndroidPlatformOpen`: `ACTION_VIEW` (http, https, mailto), files through `FileProvider` with a read grant. `openInFolder` is `PlatformUnavailable` (no such Android action) |
| Notifications | `AndroidNotificationService`: `NotificationManagerCompat`, channels `puklic.messages` / `puklic.mentions` (urgent) / `puklic.voice`; `POST_NOTIFICATIONS` requested on the first notification. No actions/images advertised (no way to deliver an action tap back) |
| Presence | `AndroidPlatformPresence`: away = screen off (`PowerManager.isInteractive`), DND = interruption filter, kept current by system broadcasts |
| Auto start | Not supported by design (`supported = false`, `PlatformUnavailable`), same as iOS; Android 14+ forbids a microphone foreground service from `BOOT_COMPLETED` |
| Tray | None on Android; `AndroidTrayService` raises `PlatformUnavailable` and is not wired into the graph (no shared code consumes a tray) |
| hCaptcha login | `CaptchaWebView.android.kt`: `android.webkit.WebView` in `AndroidView`, the shared `captchaHtml` page (hCaptcha Enterprise explicit render with `rqdata`, ADR 0002) and a `captcha` JavaScript interface that posts the token back; the shared login screen is reused unchanged |
| Runtime permissions | `RuntimePermissions`: requested only on first need (`RECORD_AUDIO` + notifications on the first voice join, notifications on the first notification), never at startup; the started `MainActivity` attaches the launcher |
| Voice session | `PermissionGatedVoiceClient` (permission gate around any `VoiceClient`) + `VoiceServiceController` runs `VoiceForegroundService` (type `microphone`, one low-importance notification with a "Disconnect" action) exactly while the voice state is connecting, ringing or connected |
| File picker | Storage Access Framework (`ACTION_OPEN_DOCUMENT`), not wired yet |
| Voice engine | `SharedVoiceClient` (`:shared:voice-codec` commonMain) behind `PermissionGatedVoiceClient`, wired in `AndroidVoiceClientFactory`; announces DAVE version 0 (no end-to-end encryption), transport encryption `aead_xchacha20_poly1305_rtpsize`; no screen sharing |
| Audio capture / playback | `AndroidAudioCapture` (`AudioRecord`, `VOICE_COMMUNICATION` source) and `AndroidAudioPlayback` (`AudioTrack`, voice-communication usage), 48 kHz mono 16-bit, 20 ms frames |
| Opus | Concentus (pure Java, BSD-3-Clause), no NDK and no per-ABI natives |
| Audio route | `VoiceAudioRouting`: audio focus, `MODE_IN_COMMUNICATION`, headset else loudspeaker; driven by `VoiceServiceController` together with the foreground service |
| Voice UI | `VoiceDock` actual: one bottom row shown only while in a voice channel, "channel · server" on the left, mute, deafen and disconnect icon buttons on the right; no screen-share button, no DAVE indicator |
| Push notifications | FCM — **out of scope** (decided 2026-11-26, PUKLIC-129): Discord has no push for user accounts and a relay server would have to hold the Discord token. Notifications are raised locally from the gateway while the app or its foreground service runs |

## Process / lifecycle

Discord gateway = persistent WebSocket. Android aggressively kills background apps. Current behaviour:
- Without a voice session the app suspends in the background → gateway disconnects → reconnects on return
- During a voice session the microphone foreground service keeps the process alive
- A background gateway service with a "Background mode" toggle is a possible later addition

## Permissions

| Permission | Purpose | Requested |
|---|---|---|
| `INTERNET` | Network | install time |
| `ACCESS_NETWORK_STATE` | Connectivity changes | install time |
| `RECORD_AUDIO` | Voice | runtime, first voice join |
| `MODIFY_AUDIO_SETTINGS` | Communication audio mode and route during a call | install time |
| `POST_NOTIFICATIONS` | Notifications and the voice notification (Android 13+) | runtime, first voice join or first notification |
| `FOREGROUND_SERVICE` | Voice session service | install time |
| `FOREGROUND_SERVICE_MICROPHONE` | Voice session service type (Android 14+) | install time |

The microphone hardware feature is declared `required=false` so Play does not filter out devices.

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

The only foreground service is the voice one (`VoiceForegroundService`, channel `puklic.voice`, `IMPORTANCE_LOW`): ongoing notification "Puklic / Connected to voice", tap returns to the app, action "Disconnect" ends the voice session through `VoiceDisconnectHandler` (implemented by `PuklicApp`). It is started only while a voice session is in progress. There is no background gateway service: the gateway runs while the app process is alive.

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
