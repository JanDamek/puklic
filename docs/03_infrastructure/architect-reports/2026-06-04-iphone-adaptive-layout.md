# iPhone Adaptive Layout — UX Design Proposal (Issue #95)

Date: 2026-06-04
Author: UX architect
Status: PROPOSAL — requires user approval before implementation
Mode: read-only design (no code changes)

## 1. Current layout analysis

Layout root: `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainScreen.kt`.

`MainScreen` (line 100) is a hardcoded three-pane `Row` with **no responsive logic at all** — no
`BoxWithConstraints`, no `WindowSizeClass`, no breakpoints. The structure:

| Pane | Composable | Location | Width |
|---|---|---|---|
| Guild rail | `GuildRail` | MainScreen.kt:107 (def :306) | **56 dp fixed** (`Modifier.width(56.dp)`) |
| (divider) | `VerticalDivider` | :118 | 1 dp |
| Channel/DM list column | `Column` wrapping `ChannelListPane`/`DmListPane` + `VoiceDock` + `UserInfoRowMount` | :119 (panes def :409/:501) | **240 dp fixed** (`Modifier.width(240.dp)`) |
| (divider) | `VerticalDivider` | :145 | 1 dp |
| Message pane | `MessagePane` | :158 (def :654) | `fillMaxWidth()` — takes the rest |

Key facts:
- The outer `Box` (:105) also hosts overlays (snackbar, dialogs, settings overlay, incoming-call).
- **There is no separate members/voice "fourth pane."** Voice members render *inline* inside
  `ChannelListPane` via `VoiceChannelEntry` (:860) under each voice channel. The "members list"
  Discord shows on the right does not exist yet in Puklic, so the adaptive design only has to handle
  **three** logical regions: guild rail, channel/DM list, message pane.
- DM vs guild mode is a single boolean `state.isDmHome`; the middle column swaps `DmListPane` ↔
  `ChannelListPane`. Selection state (`selectedGuildId`, `selectedChannelId`, `isDmHome`) all lives
  in `MainViewModel` (`MainScreenState`, MainViewModel.kt:70), so navigation/back state can be
  derived from existing VM state — no new navigation graph strictly required.
- Mount site: `PuklicApp.kt:102` calls `MainScreen(viewModel, platformOpen)` — single call site,
  easy to wrap.

Sum of fixed chrome = 56 + 1 + 240 + 1 = **298 dp** before the message pane gets anything. On a
390 dp iPhone that leaves ~92 dp for messages — unusable. This is the bug in #95.

### Existing design-system doc

`docs/04_ui/adaptive-layouts.md` **already specifies the intended adaptive system** (Material 3
window size classes; Compact <600 / Medium 600–839 / Expanded ≥840; three-screen stack on compact;
drawer on medium; resizable 200–320 dp channel column on expanded). The doc even sketches a
`PuklicApp()` with `CompactScaffold/MediumScaffold/ExpandedScaffold`. **None of this is implemented.**
This proposal reconciles that doc with the smallest-change principle.

## 2. Screen-size detection available on iOS

- No size detection is used today anywhere in `shared/compose-ui`.
- `gradle/libs.versions.toml` declares `compose-material3-adaptive = "...adaptive:1.1.0"`
  (lines 32 / 115) but it is **not** added to `shared/compose-ui/build.gradle.kts` (only
  `compose.material3` is). So `WindowSizeClass` is not currently on the classpath of the UI module.
- Two viable detection mechanisms, both KMP/iOS-safe:
  1. **`BoxWithConstraints`** — zero new dependencies, gives `maxWidth: Dp` directly at the layout
     site. Works identically on iOS, Android, Desktop. Simplest possible.
  2. **`WindowSizeClass`** via `org.jetbrains.compose.material3.windowsizeclass` (would need adding
     `calculateWindowSizeClass()` + the dep to compose-ui). Matches the doc, but adds a dependency
     and an experimental API for what is essentially a width threshold.
- `LocalWindowInfo.current.containerSize` also works but returns px (needs density conversion) — no
  advantage over `BoxWithConstraints`.

Recommendation for detection: **`BoxWithConstraints`** with two `Dp` thresholds. It is the smallest
change, dependency-free, and the breakpoints can still be the doc's 600/840 values.

## 3. Proposed adaptive designs (alternatives)

All options share one principle from the doc and the user's constraint: **the existing pane
Composables (`GuildRail`, `ChannelListPane`, `DmListPane`, `MessagePane`, `VoiceDock`,
`UserInfoRowMount`) are reused unchanged. The adaptive layer only changes how they are arranged.**
The `width(56.dp)`/`width(240.dp)` modifiers move out into the adaptive wrapper so the same panes can
also be rendered full-width.

Breakpoints (by available width, via `BoxWithConstraints.maxWidth`):
- Compact: `< 600 dp` (iPhone portrait, ~390 dp)
- Medium: `600–839 dp` (iPhone Max landscape ~932 capped? actually lands in Expanded; small iPad / split view)
- Expanded: `>= 840 dp` (iPad, desktop) — current behaviour

---

### Option A — Discord-style drawer + back nav (RECOMMENDED) [PRIMARY]

Compact: a single full-screen `MessagePane`. The guild rail + channel list live in a **left drawer**
(`ModalNavigationDrawer` or a hand-rolled offset overlay) that slides over. The message header gets a
leading hamburger / back affordance to open the drawer; selecting a channel closes the drawer and
shows messages full-screen. Exactly Discord mobile. Voice members stay inline in the channel list
(already the case), so no separate members surface is needed. When no channel is selected, the drawer
is shown open by default (so the user always sees somewhere to tap).

```
COMPACT (<600) — drawer closed (reading)        COMPACT — drawer open (switching)
+------------------------------------+          +----------------+-------------------+
| [=]  #general            [phone?]  |          |G | Channels    | (#general dimmed) |
|------------------------------------|          |G |  # general  |                   |
|                                    |          |G |  # random   |                   |
|  messages (full width)             |          |G |  v Voice    |                   |
|                                    |          |--|             |                   |
|                                    |          |+ | [VoiceDock] |                   |
|------------------------------------|          |  | [UserInfo ] |                   |
| [+] Message #general            >  |          +----------------+-------------------+
+------------------------------------+           tap channel -> drawer closes
```

Medium (600–839): guild rail (56 dp) always visible; channel list collapsible drawer over messages,
toggle in header — matches doc's Medium.

```
MEDIUM (600-839)
+--+---------------------------------------+
|G | [=] #general                          |
|G |---------------------------------------|
|G |   messages                            |
|--|                                       |
|+ |   (channel drawer slides from left)   |
+--+---------------------------------------+
```

Expanded (>=840): the current three-pane Row, unchanged.

```
EXPANDED (>=840)
+--+-----------+----------------------------+
|G | Channels  | #general                   |
|G |  #general | messages                   |
|G |  #random  |                            |
|--|           |                            |
|+ | [Voice ]  | [+] Message #general       |
|  | [User  ]  |                            |
+--+-----------+----------------------------+
 56     240               rest
```

Pros: matches user mental model (Discord), best one-handed phone UX, reuses every pane, no new nav
library. Drawer state derives from `selectedChannelId` + a local `drawerOpen` flag.
Cons: drawer/gesture wiring is the most code of the options (still modest — one `BoxWithConstraints`
+ one drawer composable). Guild rail + channel list share the drawer on compact (two regions in one
drawer) — needs the rail rendered inside the drawer.

---

### Option B — Three-screen back-stack (the doc's literal Compact spec)

Compact: three sequential full-screen screens — Guilds -> Channels -> Messages — with a top app bar
back button, driven by the existing VM selection state (`isDmHome`, `selectedGuildId`,
`selectedChannelId`) acting as the "stack depth." No drawer, no overlay.

```
COMPACT screen 1: Guilds      screen 2: Channels (back)     screen 3: Messages (back)
+------------------------+    +------------------------+    +------------------------+
| Servers                |    | < My Server            |    | < #general    [phone?] |
|------------------------|    |------------------------|    |------------------------|
| (DM) Direct Messages   |    |  # general             |    |  messages full width   |
| [icon] My Server       |    |  # random              |    |                        |
| [icon] Another Server  |    |  v Voice               |    |------------------------|
| [+] Add friend / join  |    | [Voice] [User]         |    | [+] Message #general   |
+------------------------+    +------------------------+    +------------------------+
```

Medium / Expanded: identical to Option A (drawer / three-pane).

Pros: simplest possible compact reading screen; clearest back semantics; matches the written doc.
Cons: switching guild->channel is two taps + two screen transitions (slower than Discord's drawer);
guild rail's vertical icon strip becomes a list screen, a bigger visual redesign than A; gesture
"swipe right to reveal channels" that users expect from Discord is absent.

---

### Option C — Minimal "collapse-to-two-then-one" with no drawer (smallest diff)

Pure `BoxWithConstraints` switch, no drawer composable, no gestures. Three branches that just
re-arrange the existing panes:
- Expanded (>=840): current three-pane Row (unchanged).
- Medium (600–839): two-pane — rail + (channel list OR messages depending on whether a channel is
  selected); a back chip in the message header clears selection to return to the list.
- Compact (<600): one pane at a time — show channel/DM list (with a thin guild rail strip on top or
  left) until a channel is selected, then show messages full-screen with a back affordance. Selection
  toggles which pane is composed. No animation, no overlay.

```
COMPACT, nothing selected      COMPACT, channel selected
+--+---------------------+     +------------------------+
|G | Channels            |     | < #general    [phone?] |
|G |  # general          |     |------------------------|
|G |  # random           |     |  messages full width   |
|--|                     |     |                        |
|+ | [Voice] [User]      |     | [+] Message #general   |
+--+---------------------+     +------------------------+
```

Pros: absolute smallest change — one `BoxWithConstraints`, a couple of `if` branches, zero new
deps/composables, no gesture code. Reuses panes verbatim. Back = clear `selectedChannelId`.
Cons: less polished than Discord (no slide-over, no swipe); guild switching on compact requires the
rail to be co-visible with the channel list (handled by keeping the 56 dp rail beside the list in the
list state). Functionally correct and fixes #95, but not the "feels native" bar.

---

### Option D — Material3 adaptive `NavigableListDetailPaneScaffold`

Use `compose.material3.adaptive`'s `ListDetailPaneScaffold` (the declared-but-unused dep). It handles
list/detail collapse + back automatically across size classes.

Pros: framework does the breakpoint + back-handling work; "official" adaptive pattern.
Cons: it models **two** panes (list + detail); Puklic has three logical regions (rail + list + msg),
so the guild rail must be bolted on outside the scaffold anyway. The API is experimental and shapes
the navigation in ways that may fight the existing VM-driven selection. Largest conceptual change for
a UI that is already 90% custom. Not recommended for the smallest-change goal.

## 4. Recommendation

**Primary: Option A (Discord-style drawer + back nav).** It is what users expect from a Discord
client, gives the best one-handed iPhone experience, satisfies issue #95, and still reuses every
existing pane Composable unchanged — the adaptive layer is one `BoxWithConstraints` selecting between
three arrangements plus one drawer composable for compact/medium. It also implements the existing
`docs/04_ui/adaptive-layouts.md` intent for Medium/Expanded verbatim.

**If minimal effort is the overriding priority, fall back to Option C** — it fixes the unusable
narrow-screen layout with the least code and no new dependency, and can be upgraded to A later (the
panes don't change, only the wrapper does).

Detection: `BoxWithConstraints` with `maxWidth` thresholds at 600 dp and 840 dp (dependency-free).
Avoid adding `WindowSizeClass`/material3-adaptive unless Option D is chosen.

### Column resize (user explicitly asked)

Only meaningful in **Expanded**. Recommend a draggable `VerticalDivider` between the channel list and
message pane, clamping the channel column to 200–320 dp (the doc's range), persisted via the existing
`UserPreferencesRepository`. This is an **independent, additive enhancement** to the Expanded branch
and can ship after the adaptive collapse — it does not affect Compact/Medium. Suggest treating it as
a separate follow-up so #95 (the iPhone fix) isn't blocked on it.

### What changes vs what stays

- New: an adaptive wrapper (e.g. `AdaptiveMainLayout`) inside `MainScreen` that branches on
  `BoxWithConstraints.maxWidth`; for compact/medium a drawer hosting `GuildRail` + channel/DM list.
- Moved: the `width(56.dp)` / `width(240.dp)` modifiers move from the `Row` into the wrapper so panes
  can also fill width.
- Unchanged: `GuildRail`, `ChannelListPane`, `DmListPane`, `MessagePane`, `VoiceDock`,
  `UserInfoRowMount`, all overlays, and `MainViewModel`/`MainScreenState`. A message-header back/menu
  affordance is the only addition inside `MessagePane`/`ChannelMessages`.

## Critical files for implementation

- shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainScreen.kt
- shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainViewModel.kt
- shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/PuklicApp.kt
- docs/04_ui/adaptive-layouts.md
- shared/compose-ui/build.gradle.kts
