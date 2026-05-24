# Screen inventory

Inventory of screens + their states for MVP (Phase 1). Every screen has a state machine with at minimum: **Loading / Content / Empty / Error**.

## Screen map

```
RootScreen (decides based on session state)
  ├─ LoginScreen
  └─ MainScreen (authenticated)
       ├─ GuildRail (always visible on Expanded)
       ├─ ChannelList (selected guild)
       ├─ MessageList (selected channel)
       │    └─ Composer (inline bottom)
       ├─ SettingsOverlay (modal — full screen)
       │    ├─ AccountSettings
       │    ├─ AppearanceSettings
       │    ├─ NotificationSettings
       │    ├─ StorageSettings
       │    ├─ KeybindingsSettings
       │    └─ AboutSettings
       └─ CommandPaletteOverlay (modal — Ctrl+K)
```

---

## LoginScreen

First screen after startup if no token is stored.

### Layout

Single centered card 480×auto dp. Full-screen in Compact.

```
┌───────────────────────────────────────┐
│              Puklic                   │
│      (logo or wordmark)               │
│                                       │
│   Sign in with a Discord token        │
│                                       │
│   ┌───────────────────────────────┐   │
│   │ Paste your token...           │   │
│   └───────────────────────────────┘   │
│                                       │
│   ⚠ Your token grants full access     │
│     to your account. Never share it.  │
│                                       │
│   [How do I find my token?]           │
│                                       │
│         [   Sign in   ]               │
└───────────────────────────────────────┘
```

### States

| State | Description |
|---|---|
| Idle | Empty input, button disabled |
| Typing | Input has content, button enabled |
| Validating | Show spinner on button, disable input. REST `GET /users/@me` request running. |
| Error — invalid token | Red helper text under input: "Token rejected by Discord (401)" |
| Error — network | Red helper text: "Cannot reach Discord. Check connection." |
| Success | Brief fade-to-MainScreen transition (200 ms) |

### Interactions

- Enter in input → submit (if token != empty)
- Escape → no-op (nowhere to go back to)
- "How do I find my token?" → external browser to docs page (GitHub wiki for now)
- Token in input = `obscureText` mode (like a password)
- Paste detection: on paste event auto-trim whitespace
- No "Show token" toggle (security)

---

## MainScreen — three-pane (Expanded)

### Layout

| Pane | Width | Component |
|---|---|---|
| Guild rail | 56 dp | `GuildRail` |
| Channel list | 240 dp (resizable 200–320) | `ChannelListPane` |
| Messages | rest | `MessagePane` |

No global top ribbon. Channel name + actions are in the `MessagePane` header.

### GuildRail

Vertical icon list. Items:
- **Home / DMs** (top, fixed) — aggregates DMs across all guilds
- **Per guild icon** (32 dp, round) with a presence dot if it has unread
- **+ Add server** (bottom, fixed) — disabled in MVP (read-only Discord server creation)

States per guild item:
- Default — icon at 80 % opacity
- Hover — full opacity + name tooltip
- Selected — full opacity + 4 dp `primary` indicator left edge
- Has unread — small white dot top-right
- Has mention — badge with count, `mention` color

Selection persisted in `StateFlow<NavigationState>` — survives reconnect.

### ChannelListPane

Header (40 dp tall):
- Guild name (titleMedium)
- Settings icon (⚙) right — opens guild context menu (Phase 2+)

Body: scrollable list, items grouped under collapsible category headers.

Category header:
- 24 dp tall, label `Category labels` (uppercase 11 sp)
- Click → toggle collapse, state persisted per (guild, category)

Channel item (28 dp tall):
- 12 dp left padding
- 16 dp leading icon (`#` text, `🔊` voice, `🧵` thread)
- 14 sp label
- Right-aligned: unread badge (count) or mention badge

States:
- Default
- Hover — background `surfaceVariant`
- Selected — background `primaryContainer`, text `primary`
- Muted — opacity 0.5
- Unread — bold text + left edge 2 dp `onSurface` indicator

### MessagePane

#### Header (44 dp)

- Left: channel `#` icon + name (titleMedium)
- Center-left: channel topic (bodySmall, ellipsized) — Phase 2
- Right: actions — Pinned (📌), Members (👥, toggle right pane Phase 2+), Search (🔍 Phase 2)

#### Message list

Scrollable, lazy loaded. Reverse-chronological from bottom. Auto-scroll to bottom on new message if user is near bottom (last visible item is within 100 dp of bottom edge).

States:
- **Loading initial** — full-pane skeleton (3-5 fake rows shimmer)
- **Loaded with messages** — render
- **Loaded empty** — centered placeholder: "No messages yet" + small icon
- **Loading more (scroll up)** — small spinner at top
- **Error loading more** — red banner at top: "Failed to load older messages [Retry]"

Each message row:
- 32 dp round avatar (top-aligned)
- 12 dp gap
- Header row: username (titleSmall) + timestamp (bodySmall, `onSurfaceVariant`)
- Body: RichTextView over `parsedContent`
- Reactions chips below body (Phase 2)
- Hover: row gets `surfaceVariant` background + actions overlay (react, edit if own, delete, copy link, more...)

Message grouping:
- If same author as previous + timestamp diff < 5 min → no avatar, no header, gap between rows 2 dp
- Otherwise full row with avatar, gap 12 dp

Optimistic states:
- **Sending** — opacity 0.6, no actions on hover
- **Failed** — red ⚠ icon left of timestamp, retry option in context menu

#### Composer

Bottom-anchored, full width minus 16 dp margin.

Layout (vertical):
```
┌───────────────────────────────────────┐
│ [B] [I] [S̲] [<>] [🔗] [😊]            │  ← toolbar, 32 dp
├───────────────────────────────────────┤
│ Message #general...                   │  ← input, auto-grow 40-200 dp
│                                       │
└───────────────────────────────────────┘
```

Toolbar buttons:
- **B** Bold (Ctrl+B)
- **I** Italic (Ctrl+I)
- **S̲** Strikethrough (Ctrl+Shift+S)
- **<>** Code (Ctrl+E inline, Ctrl+Shift+E block)
- **🔗** Link (Ctrl+K... collision with command palette, use Ctrl+Shift+L)
- **😊** Emoji picker (opens overlay)
- **+** Attachment (Phase 2)

Input:
- Auto-grow 1–10 lines, then internal scroll
- Submit: Enter (single line) / Ctrl+Enter / Cmd+Enter (multi-line)
- Shift+Enter: line break
- Escape: blur composer (clear focus)
- Trailing Send `IconButton` (paper-plane icon) rendered at row end, bottom-aligned; visible always, disabled when draft is blank or composer disabled. Keyboard-first send remains primary; the button is a discoverability and pointer-only fallback (issue #12).
- `@` triggers user mention autocomplete (Phase 2)
- `#` triggers channel mention autocomplete (Phase 2)
- `:` triggers emoji shortcode autocomplete (Phase 2)
- Markdown rendered in message list after submit, not in the composer

Draft persistence: every 500 ms debounced → `local_draft` table per channel.

---

## SettingsOverlay

Modal, full-screen overlay with padding (16 dp each side on Expanded, 0 on Compact).

### Layout

```
┌─Settings──────────────────────────[X]┐
│                                       │
│ ┌─────────────┬───────────────────┐   │
│ │ Account █   │ Account           │   │
│ │ Appearance  │                   │   │
│ │ Notifs      │ ...content...     │   │
│ │ Storage     │                   │   │
│ │ Keybindings │                   │   │
│ │ About       │                   │   │
│ └─────────────┴───────────────────┘   │
└───────────────────────────────────────┘
```

Left nav: 240 dp, selected item background `primaryContainer`.
Right content: scrollable, 24 dp padding.

Categories MVP:

#### Account
- Avatar (64 dp) + Username + Global name + Discriminator
- Email (read-only display)
- Token expiration: "—" (unknown for user tokens)
- [Log out] button — destructive style, confirmation dialog

#### Appearance
- Theme: Dark (only, light disabled in MVP with "Coming in Phase 2" hint)
- Density: Compact (only option in MVP, dropdown disabled)
- Accent color (Phase 2 — disabled)

#### Notifications
- Master toggle: "Show notifications"
- "Notify for: All messages / Mentions only / Nothing" radio
- Sound on notification: toggle
- (Per-channel overrides: Phase 2)

#### Storage
- Cache sizes table:
  - Attachments: X MB / 500 MB [slider 100–5000 MB]
  - Images: X MB / 200 MB [slider 50–1000 MB]
  - Custom emoji: X MB / 50 MB
  - Stickers: X MB / 100 MB
  - Database: X MB
- [ Clear cache ] button
- [ Wipe local database ] button (destructive, double-confirm)
- Disable disk cache: toggle (warning shown)

#### Keybindings
- Read-only list of all shortcuts (default).
- Custom rebinding: Phase 2

#### About
- "Puklic vX.Y.Z"
- License (Apache 2.0)
- Acknowledgments (libraries used)
- GitHub link
- Disclaimer about Discord ToS

### Close

- [X] button top-right
- Escape key
- Click outside modal (Phase 2 — optional, may be confusing)

---

## CommandPaletteOverlay

Modal overlay, opens on Ctrl+K (Cmd+K on macOS).

### Layout

Centered, 640×480 dp on Expanded. Anchored 25 % from top.

```
┌─🔍─Search-channels,-users,-commands───┐
├───────────────────────────────────────┤
│ # general                       ↵     │
│ # off-topic                           │
│ @ alice                               │
│ ─────────────────                     │
│ ⚡ Toggle dark mode              [⌘D] │
│ ⚡ Log out                            │
│ ⚡ Open settings                 [⌘,] │
│ ⚡ Clear cache                        │
└───────────────────────────────────────┘
```

### Behavior

- Open: Ctrl+K
- Close: Escape, or selection
- Type: filter results live (fuzzy match)
- Arrow keys ↑↓: navigate; Enter: execute selected
- Default results (empty query): recent channels + common commands
- Result types:
  - Channels (`#`)
  - Direct messages (`@`)
  - Servers (👥)
  - Commands (⚡)
- Shortcuts in right column where available

### States

- Open with empty query → default results
- Typing → filtered
- No matches → "No results" + suggestion to use slash command (Phase 2)

---

## Error / disconnected states

Global banner above MessagePane (top edge):

| State | Color | Text |
|---|---|---|
| Connecting | `surfaceVariant` | "Connecting to Discord..." |
| Reconnecting | `surfaceVariant` | "Reconnecting in 3s... [Retry now]" |
| Offline | `error` (dim) | "Offline — read-only mode. Messages will send when you reconnect." |
| Token expired | `error` | "Session expired. [Sign in again]" → triggers logout |

---

## Empty states (visual)

Standard pattern: small icon (48 dp, `onSurfaceVariant`) + heading + body + optional CTA, centered in pane.

| Pane | Empty state |
|---|---|
| GuildRail (no guilds) | (rare for real users) "No servers yet" |
| ChannelList (no channels visible) | "No channels here" |
| MessagePane (no channel selected) | "Select a channel to start chatting" |
| MessagePane (channel selected, no messages) | "It's quiet here. Start the conversation." |
| CommandPalette (no results) | "Nothing matches '$query'" |

---

## Open questions

- Avatar fallback styling (gradient by ID? plain initials on accent?)
- Logo / wordmark design (placeholder on LoginScreen)
- Empty state illustrations vs plain text
- Loading skeletons vs spinners — convention TBD per component (lean to skeletons for content, spinners for actions)
