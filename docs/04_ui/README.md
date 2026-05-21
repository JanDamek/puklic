# UI / UX

The UX foundation has been settled by discussion. This section holds the UI specification at the level needed to start Phase 1 implementation. Details (exact hex values, fonts, login wireframe pixels) are worked out as we go.

## Documents

| Document | Contents |
|---|---|
| [`design-system.md`](design-system.md) | Color/typography/spacing/shape tokens, dark theme palette, avatar and presence specs |
| [`adaptive-layouts.md`](adaptive-layouts.md) | Compact / Medium / Expanded breakpoints, three-pane collapse strategy |
| [`screens.md`](screens.md) | Screen inventory (Login, Main three-pane, Settings, Command palette) + states |
| [`component-library.md`](component-library.md) | Reusable Compose components (Avatar, MessageRow, Composer, CommandPalette, ...) |
| [`interactions.md`](interactions.md) | Keyboard shortcuts, gestures, focus management, accessibility |

## Fixed decisions (UX discussion 2026-05-21)

| Aspect | Choice |
|---|---|
| **Desktop layout** | Three-pane Discord-style (guild rail \| channels \| messages) |
| **Density** | Compact (32 dp avatars, flat list, no bubbles) |
| **Theme** | Dark only in MVP, light added in Phase 2 |
| **Visual language** | Material 3 baseline + Puklic accent (specific hex TBD) |
| **Avatar shape** | Round (circle) |
| **Composer** | Inline always-visible with formatting toolbar (B/I/S/code/link/emoji) |
| **Settings** | Full-screen overlay with left category nav (Discord-style) |
| **Channel list** | Collapsible categories (category expand/collapse) |
| **Animations** | Subtle — Material default easing, no bouncy springs |
| **Keyboard nav** | Power-user heavy + `Mod+K` command palette + standard shortcuts |

These decisions are elaborated further in ADR / detail docs linked above.

## Rules that remain in effect

- UI must not parse or transform data ([CLAUDE.md](../../CLAUDE.md))
- RichText render = Composable only over a finished AST ([richtext-ast.md](../02_domain/richtext-ast.md))
- State = `StateFlow` in ViewModel ([threading-model.md](../01_architecture/threading-model.md))
- Compose Multiplatform = one UI codebase for Desktop/Android/iOS ([ADR-0001](../01_architecture/adr/0001-compose-mpp-everywhere.md))
- Adaptive layouts (Compact / Medium / Expanded) — Material 3 window size classes ([adaptive-layouts.md](adaptive-layouts.md))

## Open details (decided as we go)

- Specific accent color hex (placeholder `#7C9CFF` in design-system.md)
- Logo / wordmark design
- Login screen onboarding flow (how to present the token-paste guide — link vs in-app step-by-step)
- Default notification preferences (mentions only vs all messages)
- Avatar fallback styling (gradient by ID vs plain initials)
- Empty state illustrations vs plain text
- Loading states convention (skeleton vs spinner per component)
- Custom keybinding rebinding (Phase 2)

These details are decided when implementing the affected component. When a decision is made, an ADR is added or the relevant doc is updated.
