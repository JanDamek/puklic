# ADR-0001: Compose Multiplatform jako jednotná UI vrstva

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic cílí na Linux desktop (primárně), Android a iOS. Potřebujeme rozhodnout, zda použít jeden UI framework přes všechny platformy, nebo sdílet jen business logiku a UI psát nativně per platforma.

## Options considered

### Option A — Compose Multiplatform všude (Desktop + Android + iOS)
**Pros:**
- Jeden UI codebase pro všechny tři platformy
- Stejný state management (Compose runtime, `remember`, `LaunchedEffect`)
- Rychlejší iterace, menší cognitive load
- Android už je production-ready, Desktop production-ready, iOS aktivně dotahováno (JetBrains tlačí iOS 1.0)

**Cons:**
- iOS UI nevypadá nativně (Material/custom místo UIKit)
- iOS Compose má rough edges — scroll inertia, text input, gesta
- Skia rendering na iOS má jiný GPU profil než SwiftUI

### Option B — Compose Desktop+Android, SwiftUI na iOS
**Pros:**
- Native iOS look-and-feel
- Stabilní iOS UX (Apple ekosystém, accessibility, dynamic type)

**Cons:**
- Dva UI codebases — duplikace stavu, navigace, formulářů
- iOS verze bude vždy pozadu featurama
- Větší údržba pro jednoho vývojáře

### Option C — Webview / Electron-like
**Pros:** Maximální sdílení.
**Cons:** Porušuje jádro projektu („no Electron"). Odmítnuto bez další diskuse.

## Decision

**Option A — Compose Multiplatform všude.**

Důvod: Puklic vyvíjí jeden člověk, prioritou je rychlý MVP a low maintenance burden. iOS uživatel dostane funkční app rychleji, i když ne pixel-perfect nativní. Pokud iOS UX bude časem bolet, refactor na Option B je možný (shared `:shared:*` zůstává beze změn).

## Consequences

- ✅ Jediný UI codebase: `:desktop:compose-ui`, `:android:app`, `:ios:app` sdílejí `:shared:compose-ui` (až bude vytvořen)
- ✅ Designový systém je jednotný (Material 3 base + custom Puklic tokens)
- ⚠️ iOS uživatelé dostanou Material-like UI, ne UIKit
- ⚠️ Závislost na JetBrains roadmapě pro Compose iOS — riziko zpoždění
- 🔒 Pro iOS Compose: použít stabilní release tracks, ne dev/EAP
- 🔒 Při návrhu komponent počítat s tím, že na iOS nebude k dispozici nativní context menu, share sheet, atd. — abstrakce přes `:shared:platform-api`

## Related

- ADR-0004: Coroutine-first state management
- `docs/04_ui/design-system.md` (TBD)
- `docs/05_platforms/ios.md` (TBD)
