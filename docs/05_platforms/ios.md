# iOS

Terciární platforma. Cíl: fáze 2/3, závisí na zralosti Compose iOS.

## Targets

- **iOS deployment target:** 14.0+
- **Xcode:** latest stable
- **Compose Multiplatform iOS:** stable channel (vyhnout se EAP/dev)

## Klíčové platform integrace

| Capability | Implementace |
|---|---|
| Secure storage | Keychain Services (přes `kotlin-native` `platform.Security`) |
| Notifications | `UNUserNotificationCenter` |
| Background gateway | **omezeno** — iOS suspendí WebSocket v backgroundu (~30 s grace, pak kill) |
| File picker | `UIDocumentPickerViewController` |
| Audio | `AVAudioEngine` (fáze 3) |
| Push | APNs (vyžaduje server-side relay — out of scope) |

## Background limitations

iOS je pro Discord-style klient mnohem omezenější než Android:
- **Žádný foreground service ekvivalent** pro běžné apps
- VoIP background mode (`com.apple.developer.networking.voip`) povoluje persistent socket, ale je vyhrazený pro telefonování — App Store reject jistý pro chat app
- BackgroundTasks framework dovoluje periodické refreshes (max ~1×/15 min)

**Strategie:**
- Foreground: gateway aktivní, full functionality
- Background: gateway disconnect po grace period, app vrací na foreground → reconnect + READY → SQLite hydratuje UI okamžitě, gateway sync async
- **Push notifikace:** UPS (User Push Service) by vyžadoval vlastní server-side komponentu, která mezi Discord gateway a APNs překládá. Out of scope.

V důsledku: iOS Puklic = „check messages when I open app" model, ne „real-time notifications" model.

## App Store distribuce risk

Discord ToS violation = vyšší riziko App Review rejection než Android Play. Apple obecně přísnější.

Plán:
- **Apple Developer Program:** $99/rok pro distribuci
- **TestFlight:** beta primárně, pro early adopters
- **App Store submission:** zkusit, ale počítat s rejection. Apple typicky odmítne třetí-stranné Discord klienty.
- **Alternative:** AltStore / sideloading přes Apple ID — uživatel installuje vlastní build (vyžaduje Apple Developer účet nebo 7-day re-signing)

## Compose iOS — známé bolesti (k 2026-05)

- Text input má rough hrany (selection, paste menu)
- Scroll inertia jiný feel než UIKit
- No native context menu — custom impl
- Accessibility (VoiceOver) partial support
- Dark mode handling OK
- HiDPI / Retina handled by Compose Skia

Workaround: nejvíc problematické komponenty (text input) re-implementovat přes `UIViewController` interop, ostatní necháme Compose.

## UI considerations

- Safe area insets (notch, home indicator) — Compose iOS má `WindowInsets.safeArea`
- Predictive back N/A (iOS používá swipe-from-edge — Compose Navigation podporuje)
- Sheets (modal): Compose Bottom Sheet OK, nativní modal presentation přes interop
- iPad: split view adaptive layouts (stejný systém jako Android tablety)

## Specifika

- Žádný tray, žádný background gateway → UX se posune více k „pull" modelu
- Notifikace přes APNs by vyžadovaly server — out of scope
- Vyhnout se features, které předpokládají background presence (typing v jiném channelu na pozadí)

## Open questions

- **Real-time experience na iOS:** bez push je app reaktivní jen v foreground. Acceptable trade-off pro MVP. Možná opt-in „relay server" feature ve fázi 5+.
- **App Store vs TestFlight pouze:** rozhodnutí dle prvního submission outcome.
- **Compose iOS stability:** re-evaluate při startu fáze 2 (pokud bude bolest, switch na SwiftUI per ADR-0001 superseding).
