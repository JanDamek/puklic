# Design system

Based on Material 3 as the technical foundation + Puklic-specific customizations. Status: **MVP draft**. Specific hex/dp values for some tokens (accent, exact spacing) are placeholders — finalized during the implementation phase.

## Foundations (fixed by UX discussion)

- **Visual baseline:** Material 3 (`androidx.compose.material3`)
- **Theme:** Dark only in MVP. Light theme added in Phase 2.
- **Density:** Compact (32px avatars, flat message list, no bubbles, tight spacing)
- **Avatar shape:** Round (circle)
- **Animations:** Subtle — Material default easing (200–300 ms standard, no bouncy springs)

## Color tokens (dark theme)

Material 3 color roles. **Placeholder hex** — final accent tuned during implementation.

| Role | Token | Default (placeholder) | Usage |
|---|---|---|---|
| Primary | `colorScheme.primary` | `#7C9CFF` (TBD — Puklic accent) | Brand accent — links, focus ring, primary buttons |
| On primary | `colorScheme.onPrimary` | `#0A1226` | Text on primary surface |
| Primary container | `colorScheme.primaryContainer` | `#2C3E66` | Hover/selected state for primary |
| Secondary | `colorScheme.secondary` | `#9BB0CC` | Secondary accent — typing indicators, badges |
| Background | `colorScheme.background` | `#0F1115` | Root background |
| Surface | `colorScheme.surface` | `#16191F` | Panel backgrounds (channels list, message list) |
| Surface variant | `colorScheme.surfaceVariant` | `#1E232B` | Elevated surfaces — composer, modals |
| Surface container | `colorScheme.surfaceContainer` | `#1A1E25` | Cards, dropdowns, command palette |
| On surface | `colorScheme.onSurface` | `#E6EAF2` | Body text |
| On surface variant | `colorScheme.onSurfaceVariant` | `#A8B0BD` | Secondary text — timestamps, metadata |
| Outline | `colorScheme.outline` | `#2A3038` | Dividers, panel borders |
| Outline variant | `colorScheme.outlineVariant` | `#1E232B` | Subtle dividers |
| Error | `colorScheme.error` | `#FF6B6B` | Errors, destructive actions |
| Mention | (custom) | `#FFD66B` | @mention highlight on text |
| Mention background | (custom) | `#3D2E0A` | @mention message left-border |
| Online | (custom) | `#43B581` | Presence dot |
| Idle | (custom) | `#FAA61A` | Presence dot |
| DND | (custom) | `#F04747` | Presence dot |
| Offline | (custom) | `#747F8D` | Presence dot |

Custom tokens (mention, presence) are outside the M3 schema — they live in `PuklicColors` extension accessible via `CompositionLocal`.

## Typography

Material 3 type scale with custom adjustments for chat:

| Token | Size / weight | Usage |
|---|---|---|
| `displaySmall` | 24 sp / 400 | Onboarding headings |
| `headlineSmall` | 20 sp / 500 | Settings sections |
| `titleMedium` | 16 sp / 500 | Channel name in header, modal titles |
| `titleSmall` | 14 sp / 600 | Username in message header |
| `bodyMedium` | 14 sp / 400 | **Message body** (primary reading text) |
| `bodySmall` | 12 sp / 400 | Timestamps, metadata |
| `labelLarge` | 14 sp / 500 | Buttons, tabs |
| `labelMedium` | 12 sp / 500 | Channel list items, tags |
| `labelSmall` | 11 sp / 500 / uppercase | Category labels (`TEXT CHANNELS`) |

**Code (inline + block):**
- Inline code: `monoFont`, 13 sp, surface variant background, 4dp horizontal padding
- Code block: `monoFont`, 13 sp, surface container background, 12dp padding

**Font choice:**
- Body / UI: System default sans-serif (Inter / Roboto / SF Pro fallback)
- Monospace: System default mono (JetBrains Mono / SF Mono / Consolas fallback)
- Custom font in Phase 2+ if warranted

## Spacing scale

Token = dp.

| Token | Value | Usage |
|---|---|---|
| `space.0` | 0 dp | |
| `space.1` | 2 dp | Micro-spacing — tag inner |
| `space.2` | 4 dp | Inline code padding, badge inner |
| `space.3` | 8 dp | **Tight default** — gap between inline elements |
| `space.4` | 12 dp | **Compact default** — message vertical spacing |
| `space.5` | 16 dp | Section padding |
| `space.6` | 24 dp | Modal padding |
| `space.7` | 32 dp | Major section gap |
| `space.8` | 48 dp | Empty state spacing |

Default inner panel padding = `space.4` (12 dp). Default message row vertical gap = `space.3` (8 dp).

## Radius / shape scale

M3 shapes:

| Token | Value | Usage |
|---|---|---|
| `shapes.extraSmall` | 4 dp | Inline code, tags, small badges |
| `shapes.small` | 8 dp | Buttons, text fields |
| `shapes.medium` | 12 dp | Cards, code blocks, command palette items |
| `shapes.large` | 16 dp | Modals, sheets |
| `shapes.extraLarge` | 24 dp | Floating elements |
| (custom) `shapes.circle` | 50% | Avatars, presence dots |

## Elevation

Dark theme uses minimal drop shadow — instead **surface tinting** (different surface color for different levels).

| Level | Component | Background |
|---|---|---|
| 0 | Channels list, message list | `surface` |
| 1 | Composer area, message hover | `surfaceVariant` |
| 2 | Settings panels, dropdowns | `surfaceContainer` |
| 3 | Modals, command palette | `surfaceContainer` + soft shadow (12dp blur, 25 % opacity) |

## Iconography

- Material Symbols Outlined (font-based, 1 file for the whole set)
- Default size: 20 dp in chat, 24 dp in action buttons, 16 dp in inline tags
- Stroke weight: 400 (normal)
- Color: `onSurfaceVariant` (default), `onSurface` (active), `primary` (selected)

Discord-specific icons (Boost crown, Nitro star, ...) are **not used** — Puklic is not a Discord client lookalike.

## Avatar specification

- **Shape:** Circle (`shapes.circle`)
- **Sizes:** 16 / 20 / 24 / 32 / 40 / 64 dp
- **Default in message row:** 32 dp
- **Default in member list:** 32 dp
- **Default in DM header:** 24 dp
- **Default in command palette result:** 20 dp
- **Default in settings → account:** 64 dp
- **Fallback:** First character of `globalName` / `username` on `surfaceVariant` background, text `onSurfaceVariant`. Never "default Discord avatars" (their blue circle assets).

**Presence dot:** Circle, 8 dp diameter, 2 dp border in the parent surface color, position bottom-right -2/-2.

## Focus & interactive states

| State | Style |
|---|---|
| Hover | Background `surfaceVariant`, transition 150 ms |
| Active / pressed | Background `surfaceContainer`, scale 0.98 |
| Focus (keyboard) | 2 dp outline `primary`, offset 2 dp |
| Selected (channel list, settings nav) | Background `primaryContainer`, text `primary` |
| Disabled | Opacity 0.38 |

**Keyboard focus ring is mandatory** — visible on everything that can be targeted with the keyboard. Compose does not show it by default on desktop; it must be forced.

## Mention highlight

When a message contains an @mention of the logged-in user:
- Left border 3 dp in `mention` (yellow)
- Background row tint: 4 % opacity `mention`
- Mention chip inside the message: background `mentionBackground`, text `mention`

## Density / sizing summary

Compact stack (3 example sizes):

| Component | Compact (MVP default) |
|---|---|
| Message row avatar | 32 dp |
| Message row vertical padding | 6 dp |
| Message rows gap (same author) | 2 dp |
| Message rows gap (different authors) | 12 dp |
| Channel list item height | 28 dp |
| Guild rail icon | 36 dp |
| Composer min height | 40 dp |
| Composer toolbar height | 32 dp |

## Implementation

- `MaterialTheme` in `:shared:compose-ui` provider, dark color scheme only in MVP
- Custom tokens (`PuklicColors`, `PuklicSpacing`) via `CompositionLocal`:

```kotlin
@Composable
fun PuklicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PuklicDarkColorScheme,
        typography = PuklicTypography,
        shapes = PuklicShapes,
    ) {
        CompositionLocalProvider(
            LocalPuklicColors provides PuklicCustomColors,
            LocalPuklicSpacing provides PuklicSpacingTokens,
            content = content,
        )
    }
}
```

Usage:

```kotlin
val mention = LocalPuklicColors.current.mention
val sp = LocalPuklicSpacing.current
Box(modifier = Modifier.padding(sp.compactPadding))
```

## Open questions (resolved as we go)

- Specific accent color hex (placeholder `#7C9CFF`) — final choice on first Compose run
- Light theme palette (Phase 2)
- Custom fonts (when and which) — Phase 2+
- Animation overrides (if Material defaults don't fit in specific places)
