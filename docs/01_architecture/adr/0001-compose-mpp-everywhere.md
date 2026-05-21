# ADR-0001: Compose Multiplatform as the unified UI layer

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic targets Linux desktop (primarily), Android, and iOS. We need to decide whether to use a single UI framework across all platforms or to share only the business logic and write the UI natively per platform.

## Options considered

### Option A — Compose Multiplatform everywhere (Desktop + Android + iOS)
**Pros:**
- One UI codebase for all three platforms
- Unified state management (Compose runtime, `remember`, `LaunchedEffect`)
- Faster iteration, lower cognitive load
- Android is production-ready, Desktop is production-ready, iOS is being actively brought up to parity (JetBrains is pushing for iOS 1.0)

**Cons:**
- iOS UI does not look native (Material/custom instead of UIKit)
- iOS Compose has rough edges — scroll inertia, text input, gestures
- Skia rendering on iOS has a different GPU profile than SwiftUI

### Option B — Compose Desktop+Android, SwiftUI on iOS
**Pros:**
- Native iOS look-and-feel
- Stable iOS UX (Apple ecosystem, accessibility, dynamic type)

**Cons:**
- Two UI codebases — duplication of state, navigation, forms
- iOS version will always lag behind on features
- Higher maintenance burden for a single developer

### Option C — Webview / Electron-like
**Pros:** Maximum sharing.
**Cons:** Violates the core of the project ("no Electron"). Rejected without further discussion.

## Decision

**Option A — Compose Multiplatform everywhere.**

Rationale: Puklic is developed by a single person; the priority is a fast MVP and a low maintenance burden. iOS users get a working app sooner, even if it is not pixel-perfect native. If iOS UX becomes painful over time, a refactor to Option B is possible (the shared `:shared:*` modules remain unchanged).

## Consequences

- ✅ Single UI codebase: `:desktop:compose-ui`, `:android:app`, `:ios:app` share `:shared:compose-ui` (once created)
- ✅ Unified design system (Material 3 base + custom Puklic tokens)
- ⚠️ iOS users get a Material-like UI, not UIKit
- ⚠️ Dependency on the JetBrains roadmap for Compose iOS — risk of delays
- 🔒 For Compose iOS: use stable release tracks, not dev/EAP
- 🔒 When designing components, account for the lack of native context menus, share sheets, etc. on iOS — abstract via `:shared:platform-api`

## Related

- ADR-0004: Coroutine-first state management
- `docs/04_ui/design-system.md` (TBD)
- `docs/05_platforms/ios.md` (TBD)
