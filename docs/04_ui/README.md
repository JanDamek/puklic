# UI / UX

UX foundation rozpravou uzavřena. Tato sekce drží UI specifikaci na úrovni potřebné pro start implementace fáze 1. Detaily (přesné hex, font, login wireframe pixels) doděláváme za pochodu.

## Dokumenty

| Dokument | Obsah |
|---|---|
| [`design-system.md`](design-system.md) | Color/typography/spacing/shape tokens, dark theme palette, avatar a presence specs |
| [`adaptive-layouts.md`](adaptive-layouts.md) | Compact / Medium / Expanded breakpointy, three-pane collapse strategie |
| [`screens.md`](screens.md) | Inventory obrazovek (Login, Main three-pane, Settings, Command palette) + states |
| [`component-library.md`](component-library.md) | Reusable Compose komponenty (Avatar, MessageRow, Composer, CommandPalette, ...) |
| [`interactions.md`](interactions.md) | Keyboard shortcuts, gestures, focus management, accessibility |

## Zafixovaná rozhodnutí (UX rozprava 2026-05-21)

| Aspekt | Volba |
|---|---|
| **Desktop layout** | Three-pane Discord-style (guild rail \| channels \| messages) |
| **Density** | Compact (32 dp avatars, flat list, no bubbles) |
| **Theme** | Dark only v MVP, light přidán ve fázi 2 |
| **Visual jazyk** | Material 3 baseline + Puklic accent (konkrétní hex TBD) |
| **Avatar shape** | Round (circle) |
| **Composer** | Inline always-visible s formatting toolbar (B/I/S/code/link/emoji) |
| **Settings** | Full-screen overlay s left category nav (Discord-style) |
| **Channel list** | Collapsible categories (kategorie expand/collapse) |
| **Animations** | Subtle — Material default easing, žádné bouncy springs |
| **Keyboard nav** | Power-user heavy + `Mod+K` command palette + standard shortcuts |

Rozhodnutí jsou dále rozvedená v ADR / detailních docs odkazovaných výše.

## Pravidla, která ostávají v platnosti

- UI nesmí parsovat ani transformovat data ([CLAUDE.md](../../CLAUDE.md))
- RichText render = jen Composable nad hotovým AST ([richtext-ast.md](../02_domain/richtext-ast.md))
- State = `StateFlow` v ViewModelu ([threading-model.md](../01_architecture/threading-model.md))
- Compose Multiplatform = jeden UI codebase pro Desktop/Android/iOS ([ADR-0001](../01_architecture/adr/0001-compose-mpp-everywhere.md))
- Adaptive layouts (Compact / Medium / Expanded) — Material 3 window size classes ([adaptive-layouts.md](adaptive-layouts.md))

## Otevřené detaily (rozhodují se za pochodu)

- Konkrétní accent color hex (placeholder `#7C9CFF` v design-system.md)
- Logo / wordmark design
- Login screen onboarding flow (jak token-paste návod ukázat — link vs in-app step-by-step)
- Default notification preferences (mentions only vs all messages)
- Avatar fallback styling (gradient by ID vs plain initials)
- Empty state ilustrace vs plain text
- Loading states convention (skeleton vs spinner per komponentu)
- Custom keybinding rebinding (Phase 2)

Tyto detaily se rozhodují při implementaci dotčené komponenty. Když přijde rozhodnutí, přidává se ADR nebo updatuje příslušný doc.
