# UI / UX

> **Status: UX diskuze čeká.** Tato sekce je záměrně nechaná jako placeholder. Před implementací proběhne separátní UX rozprava (layout, screen inventory, visual style, interaction patterns) — viz Claude session.

## Co tu bude

| Dokument | Obsah |
|---|---|
| `design-system.md` | Design tokens (barvy, typo, spacing, elevation, radii), Material 3 customization, dark/light theme |
| `screens.md` | Inventář obrazovek + jejich stavy (loading, empty, error, content) |
| `component-library.md` | Reusable Compose komponenty (MessageBubble, ChannelListItem, GuildIcon, ...) |
| `interactions.md` | Gestures, keyboard shortcuts, focus management |
| `adaptive-layouts.md` | Compact / Medium / Expanded breakpoints (phone / tablet / desktop) |

## Před UX rozpravou

Otázky, které je třeba probrat s userem:

1. **Visual style:** Material 3 baseline + custom Puklic accent? Nebo úplně vlastní design system?
2. **Density:** Discord-like compact (info-dense), nebo víc air?
3. **Theme:** Dark default, light optional. Custom theming v MVP?
4. **Layout (desktop):** Three-pane (guilds | channels | messages) jako Discord? Nebo jiný layout?
5. **Layout (mobile):** Drawer + bottom tabs? Nebo overlay panels?
6. **Animations:** Subtle (Material default) nebo expressive?
7. **Avatar styling:** Round (Discord) / squircle (Apple) / square (rare)?
8. **Composer:** Inline text input nebo modal? Markdown preview vedle? Slash commands?
9. **Messages:** Bubble (per autor) nebo flat list (Discord-style)?
10. **Settings:** Inline sidebar nebo full-screen modal?

Další otázky vyvstanou během rozpravy.

## Pravidla, která už platí

I bez UX rozhodnutí jsou tyhle pravidla nastavená architekturou:

- UI nesmí parsovat data ([CLAUDE.md](../../CLAUDE.md))
- RichText render = jen Composable nad hotovým AST ([richtext-ast.md](../02_domain/richtext-ast.md))
- State = `StateFlow` v ViewModelu ([threading-model.md](../01_architecture/threading-model.md))
- Žádné `LiveData`, žádný `ObservableField`
- Compose Multiplatform = jeden UI codebase pro Desktop/Android/iOS ([ADR-0001](../01_architecture/adr/0001-compose-mpp-everywhere.md))
- Adaptive layouts (Compact / Medium / Expanded) — Material 3 adaptive
