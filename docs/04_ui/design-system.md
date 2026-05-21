# Design system

Vychází z Material 3 jako technického základu + Puklic-specific customizace. Stav: **MVP draft**. Konkrétní hex/dp hodnoty některých tokenů (accent, exact spacing) jsou placeholdery — finalizují se v implementační fázi.

## Východiska (zafixováno UX rozpravou)

- **Visual baseline:** Material 3 (`androidx.compose.material3`)
- **Theme:** Dark only v MVP. Light theme přidán ve fázi 2.
- **Density:** Compact (32px avatars, flat message list, no bubbles, tight spacing)
- **Avatar shape:** Round (kruh)
- **Animations:** Subtle — Material default easing (200–300 ms standard, žádné bouncy springs)

## Color tokens (dark theme)

Material 3 color roles. **Placeholder hex** — final accent ladíme při implementaci.

| Role | Token | Default (placeholder) | Použití |
|---|---|---|---|
| Primary | `colorScheme.primary` | `#7C9CFF` (TBD — Puklic accent) | Brand accent — odkazy, focus ring, primary buttons |
| On primary | `colorScheme.onPrimary` | `#0A1226` | Text na primary surface |
| Primary container | `colorScheme.primaryContainer` | `#2C3E66` | Hover/selected state pro primary |
| Secondary | `colorScheme.secondary` | `#9BB0CC` | Sekundární accent — typing indicators, badges |
| Background | `colorScheme.background` | `#0F1115` | Root background |
| Surface | `colorScheme.surface` | `#16191F` | Panel backgrounds (channels list, message list) |
| Surface variant | `colorScheme.surfaceVariant` | `#1E232B` | Elevated surfaces — composer, modals |
| Surface container | `colorScheme.surfaceContainer` | `#1A1E25` | Cards, dropdowns, command palette |
| On surface | `colorScheme.onSurface` | `#E6EAF2` | Body text |
| On surface variant | `colorScheme.onSurfaceVariant` | `#A8B0BD` | Secondary text — timestamps, metadata |
| Outline | `colorScheme.outline` | `#2A3038` | Dividers, panel borders |
| Outline variant | `colorScheme.outlineVariant` | `#1E232B` | Subtle dividers |
| Error | `colorScheme.error` | `#FF6B6B` | Errors, destructive actions |
| Mention | (custom) | `#FFD66B` | @mentions highlight on text |
| Mention background | (custom) | `#3D2E0A` | @mention message left-border |
| Online | (custom) | `#43B581` | Presence dot |
| Idle | (custom) | `#FAA61A` | Presence dot |
| DND | (custom) | `#F04747` | Presence dot |
| Offline | (custom) | `#747F8D` | Presence dot |

Custom tokens (mention, presence) leží mimo M3 schema — bydlí v `PuklicColors` extension přístupném přes `CompositionLocal`.

## Typography

Material 3 type scale s vlastní úpravou pro chat:

| Token | Size / weight | Použití |
|---|---|---|
| `displaySmall` | 24 sp / 400 | Onboarding nadpisy |
| `headlineSmall` | 20 sp / 500 | Settings sekce |
| `titleMedium` | 16 sp / 500 | Channel name v header, modal titles |
| `titleSmall` | 14 sp / 600 | Username v message header |
| `bodyMedium` | 14 sp / 400 | **Message body** (primární čtecí text) |
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
- Custom font v Phase 2+ pokud bude důvod

## Spacing scale

Token = dp.

| Token | Value | Použití |
|---|---|---|
| `space.0` | 0 dp | |
| `space.1` | 2 dp | Mikrospacing — tag inner |
| `space.2` | 4 dp | Inline code padding, badge inner |
| `space.3` | 8 dp | **Tight default** — gap mezi inline elements |
| `space.4` | 12 dp | **Compact default** — message vertical spacing |
| `space.5` | 16 dp | Section padding |
| `space.6` | 24 dp | Modal padding |
| `space.7` | 32 dp | Major section gap |
| `space.8` | 48 dp | Empty state spacing |

Default vnitřní padding panelu = `space.4` (12 dp). Default messagové row vertical gap = `space.3` (8 dp).

## Radius / shape scale

M3 shapes:

| Token | Value | Použití |
|---|---|---|
| `shapes.extraSmall` | 4 dp | Inline code, tags, small badges |
| `shapes.small` | 8 dp | Buttons, text fields |
| `shapes.medium` | 12 dp | Cards, code blocks, command palette items |
| `shapes.large` | 16 dp | Modals, sheets |
| `shapes.extraLarge` | 24 dp | Floating elements |
| (custom) `shapes.circle` | 50% | Avatars, presence dots |

## Elevation

Dark theme používá minimum drop shadow — místo toho **surface tinting** (rozdílná surface barva pro různé úrovně).

| Level | Component | Background |
|---|---|---|
| 0 | Channels list, message list | `surface` |
| 1 | Composer area, message hover | `surfaceVariant` |
| 2 | Settings panels, dropdowns | `surfaceContainer` |
| 3 | Modals, command palette | `surfaceContainer` + soft shadow (12dp blur, 25 % opacity) |

## Iconography

- Material Symbols Outlined (font-based, 1 file pro celý set)
- Default size: 20 dp v chatu, 24 dp v action buttons, 16 dp v inline tags
- Stroke weight: 400 (normal)
- Color: `onSurfaceVariant` (default), `onSurface` (active), `primary` (selected)

Discord-specific ikony (Boost crown, Nitro star, ...) **nepoužíváme** — Puklic není Discord client lookalike.

## Avatar specifikace

- **Shape:** Circle (`shapes.circle`)
- **Sizes:** 16 / 20 / 24 / 32 / 40 / 64 dp
- **Default v message row:** 32 dp
- **Default v member list:** 32 dp
- **Default v DM header:** 24 dp
- **Default v command palette result:** 20 dp
- **Default v settings → account:** 64 dp
- **Fallback:** První znak `globalName` / `username` na pozadí `surfaceVariant`, text `onSurfaceVariant`. Nikdy "default Discord avatars" (jejich blue circle assety).

**Presence dot:** Circle, 8 dp diameter, border 2 dp v barvě parent surface, position bottom-right -2/-2.

## Focus & interactive states

| State | Style |
|---|---|
| Hover | Background `surfaceVariant`, transition 150 ms |
| Active / pressed | Background `surfaceContainer`, scale 0.98 |
| Focus (keyboard) | 2 dp outline `primary`, offset 2 dp |
| Selected (channel list, settings nav) | Background `primaryContainer`, text `primary` |
| Disabled | Opacity 0.38 |

**Keyboard focus ring je povinný** — viditelný na všem, co lze targetovat klávesnicí. Compose ho default neukazuje na desktopu, musí se forcovat.

## Mention highlight

Když zpráva obsahuje @mention přihlášeného uživatele:
- Levý border 3 dp v `mention` (žlutá)
- Background row tint: 4 % opacity `mention`
- Mention chip uvnitř zprávy: background `mentionBackground`, text `mention`

## Density / sizing summary

Compact stack (3 příklady velikostí):

| Component | Compact (MVP default) |
|---|---|
| Message row avatar | 32 dp |
| Message row vertical padding | 6 dp |
| Message rows gap (mezi zprávami stejného autora) | 2 dp |
| Message rows gap (mezi autory) | 12 dp |
| Channel list item height | 28 dp |
| Guild rail icon | 36 dp |
| Composer min height | 40 dp |
| Composer toolbar height | 32 dp |

## Implementace

- `MaterialTheme` v `:shared:compose-ui` provider, dark color scheme jako jediný v MVP
- Custom tokens (`PuklicColors`, `PuklicSpacing`) přes `CompositionLocal`:

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

Použití:

```kotlin
val mention = LocalPuklicColors.current.mention
val sp = LocalPuklicSpacing.current
Box(modifier = Modifier.padding(sp.compactPadding))
```

## Open questions (řeší se za pochodu)

- Konkrétní accent color hex (placeholder `#7C9CFF`) — final volba při prvním Compose runu
- Light theme palette (až fáze 2)
- Custom fonts (kdy a které) — fáze 2+
- Animation overrides (pokud Material default nesedí někde specificky)
