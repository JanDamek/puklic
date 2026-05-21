# Interactions — keyboard shortcuts, gestures, focus

Power-user keyboard navigation is first-class. Mouse-only users work fine, but Puklic does not pretend to be touch-first.

## Modifier convention

| Symbol | Linux / Windows | macOS |
|---|---|---|
| `Mod` | Ctrl | Cmd |
| `Alt` | Alt | Option |
| `Shift` | Shift | Shift |

These labels are for UI hints. In Compose bindings we use the `KeyboardModifiers` abstraction.

## Global shortcuts

| Shortcut | Action |
|---|---|
| `Mod+K` | Open command palette |
| `Mod+,` | Open settings |
| `Mod+Shift+M` | Toggle mute current channel (Phase 2) |
| `Mod+/` | Show keyboard shortcuts cheat sheet |
| `Mod+Shift+L` | Insert link in composer (avoid Ctrl+L browser-style) |
| `Mod+R` | Reload session (re-fetch READY) — debugging |
| `Mod+Q` | Quit (desktop only) |
| `F1` | Help / shortcuts |

## Navigation shortcuts

| Shortcut | Action |
|---|---|
| `Alt+↑` / `Alt+↓` | Previous / next channel in current guild |
| `Mod+Alt+↑` / `Mod+Alt+↓` | Previous / next guild |
| `Mod+1` ... `Mod+9` | Jump to guild N (rail position) |
| `Mod+Shift+T` | Switch to next unread channel |
| `Esc` | Close modal, dismiss overlay, blur input |

## Message list shortcuts

When `MessageList` has focus (auto on channel switch):

| Shortcut | Action |
|---|---|
| `↑` / `↓` | Navigate messages (focus moves) |
| `PgUp` / `PgDn` | Scroll by viewport |
| `Home` | Jump to oldest loaded |
| `End` | Jump to newest |
| `Enter` | Reply to focused message (puts ref in composer) |
| `E` | Edit focused message (if own) |
| `Delete` | Delete focused message (if own, with confirm) |
| `R` | React to focused message (opens emoji picker, Phase 2) |
| `C` | Copy focused message text |
| `Shift+C` | Copy message link |

## Composer shortcuts

When composer input has focus:

| Shortcut | Action |
|---|---|
| `Enter` | Send (single-line mode) |
| `Mod+Enter` | Send (always, regardless of multi-line) |
| `Shift+Enter` | Line break |
| `Mod+B` | Bold wrap selection (`**text**`) |
| `Mod+I` | Italic wrap (`*text*`) |
| `Mod+Shift+S` | Strikethrough wrap (`~~text~~`) |
| `Mod+E` | Inline code wrap (`` `text` ``) |
| `Mod+Shift+E` | Code block wrap (` ``` ` newline) |
| `Mod+Shift+L` | Insert link (prompts URL) |
| `Mod+Shift+.` | Spoiler wrap (`||text||`) |
| `Mod+;` | Open emoji picker |
| `Esc` | Blur composer, focus message list |
| `↑` (empty composer only) | Edit last own message in channel |
| `Tab` | Move focus forward (next focusable) |

## Command palette shortcuts

Open: `Mod+K`. Once open:

| Shortcut | Action |
|---|---|
| `↑` / `↓` | Move selection |
| `Enter` | Execute selected |
| `Esc` | Close |
| `Mod+Enter` | Execute and keep palette open (Phase 2 — multi-action mode) |
| Typing | Update query |

## Gestures (touch — Android / iOS / touchpad)

| Gesture | Action |
|---|---|
| Tap message | Open thread (Phase 2) / show actions (Compact) |
| Long-press message | Context menu (react, edit, delete, copy) |
| Swipe right on message | Reply (Compact only) |
| Swipe left on message | React shortcut (Phase 2) |
| Swipe from left edge | Open channel drawer (Medium / Compact) |
| Pull down at top | Load older messages |
| Pinch on image | Zoom (image viewer Phase 2) |

No aggressive gesture chaining — users should not have to discover a hidden gesture set.

## Mouse interactions

| Action | Target |
|---|---|
| Click | Primary action (select channel, focus input) |
| Right-click | Context menu (message, channel, user, ...) |
| Middle-click on link | Open in external browser (always) |
| Hover | Reveal action bar (message row), tooltip (icons) |
| Scroll wheel | Standard scroll |
| Mod+scroll on message list | Zoom font (Phase 2) |
| Click outside modal | Dismiss (settings, command palette) |

## Focus management

- **Auto-focus on screen change:** Channel switch → focus message list, not composer. User decides when to type.
- **Composer auto-focus** only on:
  - "/" or "@" typed anywhere (commands / mentions Phase 2)
  - Click on composer area
  - Explicit shortcut (`Mod+L` jump to composer — Phase 2)
- **Modal traps focus** inside while open
- **Tab order:** Logical reading order — guild rail → channels → messages → composer
- **Visible focus ring:** Always on. 2 dp `primary` outline offset 2 dp on whatever is focused.

## Accessibility

MVP minimums:
- All interactive elements have `contentDescription` (Compose semantics)
- Tab order matches visual order
- Color contrast: WCAG AA for body text (4.5:1 minimum)
- No information conveyed by color alone (unread = bold + indicator, not just color)
- Screen reader friendly hierarchy (Compose `Modifier.semantics`)

Phase 5+ improvements:
- Voice control compatibility
- Reduced motion respect (`LocalAccessibilityManager` query) — disable subtle animations
- High contrast theme variant
- Dynamic type / font scaling respect

## Drag & drop

Phase 2:
- Drop file into composer area → attach
- Drop image from browser → attach
- Drop channel link → composer inserts mention

Desktop only initially.

## Clipboard interactions

| Action | Behavior |
|---|---|
| Paste plain text | Insert as text in composer |
| Paste image (clipboard) | Treat as attachment (Phase 2) |
| Paste Discord URL | Detect channel/message link, render as inline mention |
| Copy from message | Plain text (markdown source) or rich (formatted) — user setting (Phase 2). Default: plain text. |

## Open questions

- **Default font size scaling:** respect OS DPI / accessibility size — default yes, fine-tune per platform
- **Reduced motion preference:** OS-level toggle integration — Phase 5
- **Custom keybinding rebinding:** Phase 2 feature. MVP ships with defaults table only.
- **Vim-like modal navigation:** out of scope (interesting later if community wants)
