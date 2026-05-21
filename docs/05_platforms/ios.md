# iOS

Tertiary platform. Target: Phase 2/3, depending on Compose iOS maturity.

## Targets

- **iOS deployment target:** 14.0+
- **Xcode:** latest stable
- **Compose Multiplatform iOS:** stable channel (avoid EAP/dev)

## Key platform integrations

| Capability | Implementation |
|---|---|
| Secure storage | Keychain Services (via `kotlin-native` `platform.Security`) |
| Notifications | `UNUserNotificationCenter` |
| Background gateway | **limited** — iOS suspends WebSocket in the background (~30 s grace, then kill) |
| File picker | `UIDocumentPickerViewController` |
| Audio | `AVAudioEngine` (Phase 3) |
| Push | APNs (requires server-side relay — out of scope) |

## Background limitations

iOS is far more restrictive than Android for a Discord-style client:
- **No foreground service equivalent** for regular apps
- VoIP background mode (`com.apple.developer.networking.voip`) allows a persistent socket but is reserved for phone calls — App Store rejection is certain for a chat app
- BackgroundTasks framework allows periodic refreshes (max ~1× per 15 min)

**Strategy:**
- Foreground: gateway active, full functionality
- Background: gateway disconnects after grace period; app returns to foreground → reconnect + READY → SQLite hydrates UI immediately, gateway sync async
- **Push notifications:** APNs would require a custom server-side component translating between the Discord gateway and APNs. Out of scope.

As a result: iOS Puklic = "check messages when I open the app" model, not a "real-time notifications" model.

## App Store distribution risk

Discord ToS violation = higher risk of App Review rejection than on Android Play. Apple is generally stricter.

Plan:
- **Apple Developer Program:** $99/year for distribution
- **TestFlight:** primary beta, for early adopters
- **App Store submission:** try, but expect rejection. Apple typically rejects third-party Discord clients.
- **Alternative:** AltStore / sideloading via Apple ID — user installs their own build (requires Apple Developer account or 7-day re-signing)

## Compose iOS — known pain points (as of 2026-05)

- Text input has rough edges (selection, paste menu)
- Scroll inertia feels different from UIKit
- No native context menu — custom implementation needed
- Accessibility (VoiceOver) partial support
- Dark mode handling OK
- HiDPI / Retina handled by Compose Skia

Workaround: re-implement the most problematic components (text input) via `UIViewController` interop; leave the rest to Compose.

## UI considerations

- Safe area insets (notch, home indicator) — Compose iOS has `WindowInsets.safeArea`
- Predictive back N/A (iOS uses swipe-from-edge — Compose Navigation supports it)
- Sheets (modal): Compose Bottom Sheet OK, native modal presentation via interop
- iPad: split view adaptive layouts (same system as Android tablets)

## Specifics

- No tray, no background gateway → UX shifts more toward a "pull" model
- Notifications via APNs would require a server — out of scope
- Avoid features that assume background presence (typing in another channel in the background)

## Open questions

- **Real-time experience on iOS:** without push the app is reactive only in the foreground. Acceptable trade-off for MVP. Possible opt-in "relay server" feature in Phase 5+.
- **App Store vs TestFlight only:** decision based on first submission outcome.
- **Compose iOS stability:** re-evaluate at the start of Phase 2 (if it becomes painful, switch to SwiftUI per a superseding ADR-0001).
