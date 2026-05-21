# ADR-0005: Decompose as the navigation library

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic needs a navigation library for Compose Multiplatform that can handle a three-pane adaptive layout (guild rail | channel list | messages) — the same pattern as Discord desktop. This layout requires:

- An independent back-stack per pane (not a global stack)
- Switching between `SINGLE` / `DUAL` / `TRIPLE` display modes based on window size class
- Working state restoration on Android process death
- Production stability on JVM desktop + Android from Phase 1; iOS (Kotlin/Native) from Phase 2

This decision is marked as "the most consequential" in the setup because the navigation library shapes the lifecycle of every ViewModel, ComponentContext, and scope across all of `:shared:compose-ui`.

## Options considered

### Option A — Decompose 3.x

Arkivanov Decompose is a KMP navigation framework oriented around component-based architecture.

**Pros:**
- `ChildPanels` API directly models the three-pane layout as `SINGLE` / `DUAL` / `TRIPLE` — an exact match with adaptive-layouts.md
- Per-pane `ComponentContext` with its own back-stack (`ChildStack`)
- `instanceKeeper` + `StateKeeper` for Android process death restoration
- Production deployments: Discord-like applications (third parties), Kotlin KMP showcase projects
- Active development and support (Arkivanov, 2024–2026)
- Natural home for ViewModels: a Decompose component IS the ViewModel equivalent — no ViewModelFactory/ViewModel lifecycle conflicts

**Cons:**
- Larger API surface than Voyager / Compose Navigation
- Learning curve: `ComponentContext`, `ChildStack`, `ChildPanels` are new concepts
- Requires manual DI into components (Koin constructor injection) — no `koinViewModel()` shortcut without boilerplate

### Option B — Voyager

Café Bazaar Voyager is a simple KMP navigation library oriented around a screen stack.

**Pros:**
- Simpler API: `Navigator`, `Screen`, push/pop
- Good KMP support (Desktop + Android + iOS)

**Cons:**
- No `ChildPanels` equivalent — a three-pane layout would require a custom navigation coordinator
- Global stack, not per-pane — back-stack logic would have to be implemented manually
- Desktop is a secondary platform for Voyager (Android-first)

### Option C — Compose Navigation (Jetpack)

Jetpack Navigation Compose is Android-first navigation ported to KMP.

**Pros:**
- Large community (Android ecosystem)
- Native Android deep link support
- `ViewModel` integration (Android Jetpack)

**Cons:**
- KMP support is young (added ~2024), Desktop has gaps in 2026
- No `ChildPanels` equivalent
- Global single-stack — a three-pane layout is not a direct abstraction
- Android-centric design: iOS and Desktop are second-class citizens

### Option D — Custom navigation

Implementing a custom navigation coordinator without a third-party library.

**Pros:**
- Full control
- No third-party dependency

**Cons:**
- Re-implements exactly what Decompose provides (`ChildPanels`, `ChildStack`, lifecycle)
- High development and maintenance cost
- Only valid if no library covers the need — Decompose covers it fully

## Decision

**Option A — Decompose 3.x.**

Rationale: The `ChildPanels` API is a direct abstraction of the three-pane layout required in adaptive-layouts.md. No other KMP library offers this abstraction — alternatives would require a custom implementation of comparable complexity. Decompose has a production track record on Desktop + Android, and iOS support (Kotlin/Native) is available for Phase 2.

## Consequences

- ✅ `:shared:compose-ui` contains a Decompose `RootComponent` + `ChildPanels` for the three-pane layout
- ✅ ViewModels live in `:shared:compose-ui` as Decompose components (presentation layer), not in `:shared:repositories` (data layer)
- ✅ `ComponentContext` is the lifecycle owner of every ViewModel — coroutines are naturally cancelled on navigation away from the screen (ADR-0004)
- ✅ Android process death restoration: `instanceKeeper` + `StateKeeper` are built into Decompose
- ⚠️ Decompose API is larger — engineers need to read the documentation before implementing `:shared:compose-ui`
- ⚠️ Koin + Decompose: do not use `koinViewModel()`, use constructor injection into Decompose components instead
- 🔒 Pin Decompose to version `3.3.0` in `libs.versions.toml`; update only after verifying `ChildPanels` stability on all platforms
- 🔒 `ChildPanels` comes from the `decompose` library (not from `compose-material3-adaptive`, which provides `ThreePaneScaffold`)

## Related

- ADR-0001: Compose Multiplatform as the unified UI layer
- ADR-0004: Coroutine-first architecture (ComponentContext lifecycle + coroutine scopes)
- `docs/04_ui/adaptive-layouts.md` — three-pane layout spec
- Spec: `docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md` §Q4
