# Adaptive layouts

Puklic runs on Desktop / Android / iOS — different window and screen sizes. The adaptive system is based on Material 3 window size classes.

## Breakpoints

Material 3 window size classes:

| Class | Width | Typical device |
|---|---|---|
| **Compact** | 0–599 dp | Phone portrait, small phone landscape |
| **Medium** | 600–839 dp | Tablet portrait, phone landscape, small desktop window |
| **Expanded** | 840+ dp | Tablet landscape, desktop |

Detection via `WindowSizeClass.calculateFromSize(...)` from `androidx.compose.material3.windowsizeclass` (KMP-compatible).

## Three-pane → adaptive collapse

Fixed UX decision: **three-pane Discord-style** (guilds rail | channels | messages). This is the expanded layout. For narrower widths we **degrade**:

```
Expanded (≥ 840 dp):
┌──┬──────────┬─────────────────────────────┐
│G │ Channels │ Messages                    │
│  │          │                             │
└──┴──────────┴─────────────────────────────┘
 36         240            rest

Medium (600–839 dp):
- guild rail visible (36 dp)
- channel list overlaid (drawer) — toggle button in channel header
┌──┬─────────────────────────────────────────┐
│G │ Messages                                │
│  │                                         │
│  │ (channels drawer slide-in when needed)  │
└──┴─────────────────────────────────────────┘

Compact (< 600 dp):
- 3 separate screens: Guilds → Channels → Messages
- Back navigation between them (drawer overlay or navigation stack)
┌─────────────────────────────────────────────┐
│ ← #general                                  │
│                                             │
│ Messages (full screen)                      │
│                                             │
└─────────────────────────────────────────────┘
```

## Per-platform defaults

| Platform | Typical start | Class |
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

Three-pane according to screen size. Widths:
- Guild rail: 56 dp fixed (36 dp icon + 10 dp padding each side)
- Channel list: 240 dp default, resizable 200–320 dp (drag separator)
- Messages: rest (min 480 dp for readability)

If window < 840 dp but > 600 dp → degrade to **Medium**.

### Medium

Two-pane with collapsible channel drawer:
- Guild rail: 56 dp fixed
- Messages: rest
- Channel list: overlay drawer (280 dp) over messages, toggle button in header (☰)

Drawer state persists per session — if it was open, open it again on reload to the same width.

### Compact

Three-screen stack navigation:
1. **Guilds screen** — full-width guild list (larger icons 48 dp + name + last activity)
2. **Channels screen** — full-width channel list for the selected guild, back button
3. **Messages screen** — full-width chat, back button

No drawers, no side panels. Navigation stack between screens (Compose Navigation / Decompose).

### Compact landscape (mobile rotation)

If the user rotates their phone in the messages screen:
- Channel switcher remains a drawer overlay (left edge swipe to open)
- No break from the messages screen — users typically just want to read, not switch channels

## Settings adaptively

Settings UX decision: **full-screen overlay with left category nav**.

| Class | Layout |
|---|---|
| Expanded | Two-pane modal: left nav (240 dp) + right content (rest). Modal overlay min 1024×640 dp with padding around. |
| Medium | Same as Expanded, but modal fills 90 % of width. |
| Compact | Two-screen stack: categories screen → selected category content (back button). |

## Command palette adaptively

Ctrl+K palette:

| Class | Layout |
|---|---|
| Expanded | Centered modal 640×480 dp, top-aligned 25 % from top |
| Medium | Same, but 90 % of width |
| Compact | Full-screen overlay with search at top, results vertically scrollable |

## Composer adaptively

| Class | Composer |
|---|---|
| Expanded / Medium | Bottom-anchored, full width of messages pane, formatting toolbar visible |
| Compact | Bottom-anchored, formatting toolbar collapsed into "+" button (expand on tap) |

## Touch vs pointer

Compact (mobile) = touch. Expanded (desktop) = pointer. Medium can be either.

| Touch | Pointer |
|---|---|
| Tap targets ≥ 48×48 dp | Tap targets ≥ 32×32 dp |
| Long-press = secondary action | Right-click = context menu |
| Swipe gestures (back, refresh) | Scrollbars visible |
| No hover states | Hover states full |

Compose detects input mode via `LocalInputModeManager` — an adapter in `PuklicTheme` switches density and tap target sizing automatically.

## Multi-window (desktop)

Desktop users may have multiple Puklic windows — Phase 5+ feature. For MVP: one window per process. Settings open as an overlay within the same window, not as a separate window.

## Implementation

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

Each Scaffold is a separate Composable with its own layout. They share the same child Composables (MessageList, ChannelList, ComposerArea) — adaptivity is at the scaffold level, not inside the components.

## Open questions

- **Tablet split-view (iPadOS / Android multi-window):** when to behave as Compact vs Medium — for now detect width only
- **Foldable phones:** TBD testing, default behavior based on width class should suffice
- **TV / 10-foot UI:** out of scope. If someone deploys on TV, Compose Desktop run → expanded layout × 1.5 scale is more-or-less usable, but not recommended
