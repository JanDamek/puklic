# Adaptive layouts

Puklic běží na Desktop / Android / iOS — různé velikosti oken a obrazovek. Adaptive systém vychází z Material 3 window size classes.

## Breakpointy

Material 3 window size classes:

| Class | Width | Typický device |
|---|---|---|
| **Compact** | 0–599 dp | Phone portrait, malá phone landscape |
| **Medium** | 600–839 dp | Tablet portrait, phone landscape, malé okno desktop |
| **Expanded** | 840+ dp | Tablet landscape, desktop |

Detekce přes `WindowSizeClass.calculateFromSize(...)` z `androidx.compose.material3.windowsizeclass` (KMP-compatible).

## Three-pane → adaptive collapse

Zafixované rozhodnutí UX: **three-pane Discord-style** (guilds rail | channels | messages). Toto je expanded layout. Pro menší šířky **degradujeme**:

```
Expanded (≥ 840 dp):
┌──┬──────────┬─────────────────────────────┐
│G │ Channels │ Messages                    │
│  │          │                             │
└──┴──────────┴─────────────────────────────┘
 36         240            zbytek

Medium (600–839 dp):
- guild rail viditelný (36 dp)
- channel list overlaynutý (drawer) — toggle button v channel header
┌──┬─────────────────────────────────────────┐
│G │ Messages                                │
│  │                                         │
│  │ (channels drawer slide-in když potřeba) │
└──┴─────────────────────────────────────────┘

Compact (< 600 dp):
- 3 oddělené screens: Guilds → Channels → Messages
- Back navigation mezi nimi (drawer overlay nebo navigation stack)
┌─────────────────────────────────────────────┐
│ ← #general                                  │
│                                             │
│ Messages (full screen)                      │
│                                             │
└─────────────────────────────────────────────┘
```

## Per-platforma defaults

| Platform | Typický start | Class |
|---|---|---|
| Linux desktop | 1280×800 → 1920×1080 | Expanded |
| macOS desktop | 1440×900 | Expanded |
| Windows desktop | 1280×720+ | Expanded |
| Android phone | 360×800 (portrait) | Compact |
| Android phone landscape | 800×360 | Medium |
| Android tablet | 1280×800 | Expanded |
| iPad | 1024×768 → 1366×1024 | Expanded |
| iPhone | 390×844 (portrait) | Compact |
| iPhone Plus/Max landscape | 932×430 | Medium |
| Foldables (open) | 700×900 | Medium / Expanded |

## Layout per class

### Expanded

Three-pane podle obrazovky. Šířky:
- Guild rail: 56 dp fixed (36 dp ikona + 10 dp padding each side)
- Channel list: 240 dp default, resizable 200–320 dp (drag separator)
- Messages: zbytek (min 480 dp pro čitelnost)

Pokud window < 840 dp ale > 600 dp → degrade na **Medium**.

### Medium

Two-pane s collapsible channel drawer:
- Guild rail: 56 dp fixed
- Messages: zbytek
- Channel list: overlay drawer (280 dp) přes messages, toggle button v header (☰)

Drawer state persistuje per session — pokud byl otevřen, otevři ho i po reload do stejné šířky.

### Compact

Three-screen stack navigation:
1. **Guilds screen** — full-width list guildů (větší ikony 48 dp + jméno + last activity)
2. **Channels screen** — full-width list channels pro vybraný guild, back button
3. **Messages screen** — full-width chat, back button

Žádné drawers, žádné side panels. Navigation stack mezi screens (Compose Navigation / Decompose).

### Compact landscape (mobile rotace)

Pokud user pootočí telefon v messages screen:
- Channel switcher zůstává drawer overlay (left edge swipe to open)
- Žádný break z messages screen — userl běžně chce jen číst, ne přepínat

## Settings adaptivně

Settings UX rozhodnutí: **full-screen overlay s left category nav**.

| Class | Layout |
|---|---|
| Expanded | Two-pane modal: left nav (240 dp) + right content (zbytek). Modal překryv min 1024×640 dp s padding kolem. |
| Medium | Stejné jako Expanded, ale modal vyplní 90 % šířky. |
| Compact | Two-screen stack: categories screen → selected category content (back button). |

## Command palette adaptivně

Ctrl+K palette:

| Class | Layout |
|---|---|
| Expanded | Centered modal 640×480 dp, top-aligned 25 % from top |
| Medium | Stejné, ale 90 % šířky |
| Compact | Full-screen overlay with search at top, results vertically scrollable |

## Composer adaptivně

| Class | Composer |
|---|---|
| Expanded / Medium | Bottom-anchored, full width of messages pane, formatting toolbar visible |
| Compact | Bottom-anchored, formatting toolbar collapsed do "+" button (expand on tap) |

## Touch vs pointer

Compact (mobile) = touch. Expanded (desktop) = pointer. Medium může být oboje.

| Touch | Pointer |
|---|---|
| Tap targets ≥ 48×48 dp | Tap targets ≥ 32×32 dp |
| Long-press = secondary action | Right-click = context menu |
| Swipe gestures (back, refresh) | Scrollbars visible |
| No hover states | Hover states full |

Compose detekuje input mode přes `LocalInputModeManager` — adaptér v `PuklicTheme` přepíná density a tap target sizing automaticky.

## Multi-window (desktop)

Desktop user může mít víc Puklic oken — Phase 5+ feature. Pro MVP: jedno okno per process. Settings se otevírá jako overlay v stejném okně, ne jako separate window.

## Implementace

```kotlin
@Composable
fun PuklicApp() {
    val windowSize = calculateWindowSizeClass()
    val inputMode = LocalInputModeManager.current

    PuklicTheme {
        when (windowSize.widthSizeClass) {
            WindowWidthSizeClass.Compact -> CompactScaffold()
            WindowWidthSizeClass.Medium -> MediumScaffold()
            WindowWidthSizeClass.Expanded -> ExpandedScaffold()
        }
    }
}
```

Každý Scaffold je samostatný Composable s vlastním layoutem. Sdílí stejné child Composables (MessageList, ChannelList, ComposerArea) — adaptivita je v scaffold úrovni, ne v komponentách.

## Open questions

- **Tablet split-view (iPadOS / Android multi-window):** kdy se chovat jako Compact vs Medium — zatím detect width only
- **Foldable phones:** TBD test, default behavior dle width class by měl stačit
- **TV / 10-foot UI:** mimo scope. Pokud někdo nasadí na TV, Compose Desktop run → expanded layout × 1.5 scale je víc-méně použitelný, ale nedoporučujeme
