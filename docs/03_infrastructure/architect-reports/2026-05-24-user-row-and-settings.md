# User Info Row & Settings — Architect Report v2 (2026-05-24, issue #8)

> Revised 2026-05-24 after Step 3 critic review. Changes vs v1:
> - Email row removed (UserSummary has no email — B1)
> - Presence dot overlay geometry rewritten (B2)
> - Pending mute/deafen machinery dropped — mic/deafen icons disabled when Idle (M2)
> - Presence colors moved to theme (M5)
> - DaveLockIcon moved to commonMain — slot pattern dropped (m1)
> - UserInfoRowState data class — fewer params (m2)
> - Logout dialog copy softened (M3)
> - PuklicAvatar gets explicit ringColor param (M4)

## Summary

Replace orphaned `LogoutBar` (MainScreen.kt:564-599) with Discord-style persistent `UserInfoRow` at sidebar bottom. Identity (avatar + name + presence) + quick controls (mic/deafen — disabled when not in voice) + DAVE lock + Settings gear. Settings overlay invoked via two new StateFlows on `MainViewModel`, no router refactor. Logout moved into `SettingsCategory.ACCOUNT` (new `AccountSettingsScreen`). `PuklicAvatar` deferred presence dot completed. `DaveLockIcon` migrated to commonMain (was jvmMain). Mic/deafen icons disabled when `VoiceState is Idle` — drops "pending intent" complexity entirely. Logout dialog says "local message cache kept". No new modules, no SQLDelight, no new gateway ops.

## 1. Goal & non-goals

**Goal (v1):** Discord-style UserInfoRow at sidebar bottom. Logout in Settings → Account. Presence dot visible.

**Non-goals (v1, deferred):**
- Op 3 outbound PRESENCE_UPDATE (status picker dropdown) — follow-up issue
- Custom status text, activity strings
- Account edits (username, avatar upload, **email display** — UserSummary has no email)
- Multi-account switcher
- Mic/deafen "pending intent" when Idle (icons disabled instead — simpler, clearer UX)
- Android/iOS layout (desktop-first)
- Cache-wipe-on-logout toggle (v2 advanced)

## 2. Module touch map

**Modified:**
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainScreen.kt` — delete `LogoutBar` (L564-599); replace L109 with `UserInfoRow(...)`. Add `SettingsOverlay` mounting in root. Grep confirms LogoutBar only at L109 (call) + L564 (def) — safe orphan.
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/components/PuklicAvatar.kt` — complete deferred presence dot. Add `ringColor` param.
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/main/MainViewModel.kt` — expose `settingsOpen`, `selectedSettingsCategory`. No pending mute/deafen flags (dropped).
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/theme/PuklicColorScheme.kt` (or theme file) — add presence color tokens (`presenceOnline`, `presenceIdle`, `presenceDnd`, `presenceOffline`).
- `shared/compose-ui/src/jvmMain/kotlin/dev/puklic/ui/components/voice/VoiceStatusBar.kt` — extract `DaveLockIcon` to commonMain (small migration).

**New:**
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/components/UserInfoRow.kt` — stateless composable + `UserInfoRowState` data class.
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/components/voice/DaveLockIcon.kt` — moved from VoiceStatusBar.kt.
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/settings/AccountSettingsScreen.kt` — ACCOUNT content.
- `shared/compose-ui/src/commonMain/kotlin/dev/puklic/ui/screens/settings/SettingsHost.kt` — category switch.

**No** new modules. **No** SQLDelight. **No** gateway ops.

## 3. UserInfoRow design

```kotlin
public data class UserInfoRowState(
    val self: UserSummary?,
    val presence: PresenceState?,
    val voiceState: VoiceState,
    val daveState: DaveUiState,
    val micMuted: Boolean,       // (Connected.selfMute) ?: false — false when Idle
    val deafened: Boolean,       // analog
)

@Composable
public fun UserInfoRow(
    state: UserInfoRowState,
    onMicToggle: () -> Unit,
    onDeafToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Params: 5 (state + 3 callbacks + modifier).

**Layout (Row, 56dp height, surface background, horizontal padding 8dp):**

```
[Avatar 32dp + presence dot] [Column: globalName ?: username + @username] [weight 1f] [Mic] [Deafen] [DaveLockIcon] [Settings]
```

- Avatar: `PuklicAvatar(self, size = 32.dp, showPresence = true, presence, ringColor = MaterialTheme.colorScheme.surface)`
- If `self == null` (READY race, expected <500ms after route to Main): skeleton (gray pill) — acceptable per critic m5 since RootComponent routed past login
- **Mic/Deafen icons DISABLED when `voiceState is VoiceState.Idle`** (no pending intent). When Idle: icons rendered greyed-out, click is no-op (or open Voice settings dialog explaining "join a voice channel to enable mic controls").
- `DaveLockIcon(state.daveState)` rendered always (icon itself decides Active/Disabled state)
- Settings gear `Icons.Outlined.Settings` → `onOpenSettings`

**Bindings in MainScreen:**

```kotlin
val self by viewModel.selfUser.collectAsState()
val presences by viewModel.presences.collectAsState()
val voiceState by viewModel.voiceState.collectAsState()
val daveState by viewModel.daveState.collectAsState()
val state = remember(self, presences, voiceState, daveState) {
    UserInfoRowState(
        self = self,
        presence = self?.let { presences[it.id] },
        voiceState = voiceState,
        daveState = daveState,
        micMuted = (voiceState as? VoiceState.Connected)?.selfMute ?: false,
        deafened = (voiceState as? VoiceState.Connected)?.selfDeaf ?: false,
    )
}
UserInfoRow(state, viewModel::toggleSelfMute, viewModel::toggleSelfDeaf, viewModel::openSettings)
```

All flows exist. `toggleSelfMute()`/`toggleSelfDeaf()` already on MainViewModel (confirm; if not, thin wrapper around `voiceClient.toggleSelfMute()`).

## 4. Layout placement

```
┌─ ChannelListPane / DmListPane (weight 1f) ──┐
├─ VoiceDock (only when VoiceState != Idle) ──┤
├─ UserInfoRow (always visible when self!=null) ┤  ← replaces LogoutBar
└──────────────────────────────────────────────┘
```

UserInfoRow always rendered when logged in. VoiceDock above when voice connected.

## 5. SettingsOverlay invocation

Two new StateFlows on `MainViewModel`:

```kotlin
private val _settingsOpen = MutableStateFlow(false)
val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()

private val _selectedSettingsCategory = MutableStateFlow(SettingsCategory.ACCOUNT)
val selectedSettingsCategory: StateFlow<SettingsCategory> = _selectedSettingsCategory.asStateFlow()

fun openSettings(category: SettingsCategory? = null) {
    // null = keep current; explicit = navigate
    category?.let { _selectedSettingsCategory.value = it }
    _settingsOpen.value = true
}
fun closeSettings() { _settingsOpen.value = false }
fun selectSettingsCategory(c: SettingsCategory) { _selectedSettingsCategory.value = c }
```

**Behavior:** Gear opens to last-selected category (Discord pattern). First open after fresh launch defaults to ACCOUNT (init value). Resolves critic n1 ambiguity.

In MainScreen root after the 3-column Row:

```kotlin
val settingsOpen by viewModel.settingsOpen.collectAsState()
val category by viewModel.selectedSettingsCategory.collectAsState()
SettingsOverlay(
    isOpen = settingsOpen,
    selectedCategory = category,
    onCategorySelect = viewModel::selectSettingsCategory,
    onClose = viewModel::closeSettings,
    content = { SettingsHost(category = category, viewModel = viewModel) },
)
```

## 6. AccountSettingsScreen

```
Column (padding spacing.space5, vertical spacing.space4):
  Row(spacing.space4):
    PuklicAvatar(self, size = 80.dp, showPresence = true, presence, ringColor = MaterialTheme.colorScheme.surface)
    Column:
      Text(self.globalName ?: self.username, titleLarge)
      Text("@${self.username}", bodyMedium, onSurfaceVariant)
  HorizontalDivider
  LabeledRow("User ID", self.id.value.toString())
  Spacer space5
  Button(
      onClick = { showLogoutConfirm = true },
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
  ) { Text("Log out") }
```

**Email row REMOVED** — `UserSummary` has no email field. Adding one would require domain change + READY payload parsing change = separate scope (follow-up issue if needed).

**Logout confirmation:**

```kotlin
AlertDialog(
    title = { Text("Log out of Puklic?") },
    text = { Text("Your Discord token will be removed from this device. " +
                  "Local message cache is kept on disk to speed up future sign-in.") },
    confirmButton = { TextButton(onClick = {
        showLogoutConfirm = false
        scope.launch { viewModel.logout() }
    }) { Text("Log out") } },
    dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } },
)
```

Wording softened per M3 — no enumeration of "guild membership, channel state". Cross-account cache scoping deferred to follow-up.

## 7. Presence dot completion

Critic B2 + M4: fix overlay geometry + parameterize ring color.

Current `PuklicAvatar` (verified at L51-78) clips outer Box to CircleShape — presence dot would be clipped. Fix:

```kotlin
@Composable
public fun PuklicAvatar(
    user: UserSummary,
    size: Dp = 32.dp,
    showPresence: Boolean = false,
    presence: PresenceState? = null,
    ringColor: Color = MaterialTheme.colorScheme.surface,  // NEW (M4)
    modifier: Modifier = Modifier,
) {
    if (!showPresence || presence == null) {
        // existing path, unchanged
        AvatarCircle(user, size, modifier)
        return
    }
    // Overlay path: outer Box NOT clipped; sized = size (dot fits within bottomEnd quadrant)
    val dotSize = size * 0.32f
    val ring = dotSize * 0.18f
    Box(modifier = modifier.size(size)) {
        AvatarCircle(user, size, Modifier)  // inner clipped Box (refactor existing inline)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(dotSize)
                .background(ringColor, CircleShape)
                .padding(ring)
                .background(presence.toDotColor(), CircleShape),
        )
    }
}

private fun AvatarCircle(user: UserSummary, size: Dp, modifier: Modifier) {
    // extracted existing L51-78 body, no behavior change
}

@Composable
private fun PresenceState.toDotColor(): Color = when (this) {
    PresenceState.ONLINE -> MaterialTheme.puklicColors.presenceOnline
    PresenceState.IDLE -> MaterialTheme.puklicColors.presenceIdle
    PresenceState.DO_NOT_DISTURB -> MaterialTheme.puklicColors.presenceDnd
    PresenceState.OFFLINE, PresenceState.INVISIBLE -> MaterialTheme.puklicColors.presenceOffline
}
```

**Presence colors live in theme** (`PuklicColorScheme` or extension on MaterialTheme) — no hardcoded hex. Dark/light theme palettes set them independently. Resolves M5.

The dot is sized at 32% of avatar diameter (≈10dp on 32dp avatar). The "ring" is a surface-colored padded inset matching Discord's donut cutout. Sized within the avatar's bounding box, no clipping issues (resolves B2).

## 8. VoiceStatusBar interaction

1. UserInfoRow always at bottom when logged in
2. VoiceStatusBar/VoiceDock conditionally above when `VoiceState != Idle`
3. **Mic/Deafen in UserInfoRow:**
   - `VoiceState is Connected` → enabled, calls `viewModel.toggleSelfMute()` / `toggleSelfDeaf()` (state mirrors `Connected.selfMute/Deaf`)
   - Otherwise → **disabled** (greyed icon, no click handler). No pending intent storage. Drops M1+M2 complexity.
4. When VoiceStatusBar visible, both rows show mic/deafen — both bind to `Connected.selfMute/Deaf` via the same StateFlow, always in sync. Buttons in either row toggle the same underlying voice state.

## 9. Op 3 self-presence sending — DEFERRED

Out of scope. Reading presence via PresenceOrchestrator suffices for the dot. Self-status picker = follow-up issue "Self status picker (Op 3 PRESENCE_UPDATE)".

v1 avatar click in UserInfoRow → opens Account settings.

## 10. Cache-on-logout decision

**KEEP cache, WIPE token.** Default unchanged. Dialog wording softened (M3): "Local message cache is kept on disk to speed up future sign-in."

Cross-account safety (one machine, multiple Discord accounts): different account login does NOT see other account's cached messages because gateway returns its own data — but **stale cache rows from previous account remain on disk** until overwritten. Acceptable v1; follow-up issue: "Per-account cache scoping". Out of scope here.

## 11. Test plan

**Acknowledged: no Compose UI test infra in commonTest currently** (critic m3). Scope:

- ViewModel-only tests (trivial, infra exists):
  - `MainViewModelSettingsTest`:
    - `openSettings(ACCOUNT)` → both flows update
    - `openSettings(null)` after previous selectSettingsCategory(STORAGE) → preserves STORAGE
    - `selectSettingsCategory(APPEARANCE)` → flow updates
    - `closeSettings` → settingsOpen=false, selectedCategory unchanged
  - `MainViewModelLogoutTest`:
    - `logout()` → `sessionManager.endSession(wipeToken=true)` called once
    - (already covered partially in current MainViewModel testing — verify or add)
- Pure-helper tests:
  - `PresenceDotColorTest` — every `PresenceState` variant maps to expected theme token (using ad-hoc test theme)

**Deferred: Compose UI tests** for UserInfoRow + AccountSettingsScreen + PuklicAvatar overlay. Setting up Compose UI test infrastructure (`runComposeUiTest` + dependencies) is itself a non-trivial separate task. Filed as follow-up: "Add Compose UI test infrastructure to commonTest."

For v1: manual smoke verification via running the desktop app.

## 12. Risks

1. **PresenceState verification** — confirmed enum: `ONLINE, IDLE, DO_NOT_DISTURB, OFFLINE, INVISIBLE` (GatewayEventSource.kt:19). Color mapping matches.
2. **DaveLockIcon move from jvmMain to commonMain** — small refactor. Verify no jvm-only imports (the current code uses Material icons + colors only, all KMP-compatible).
3. **`MainViewModel.toggleSelfMute()/toggleSelfDeaf()` existence** — must verify these methods exist before Step 5. If not, add thin wrappers around `voiceClient.setSelfMute/Deaf`. Already a low-risk fast verification.
4. **PuklicAvatar refactor extracting AvatarCircle** — must preserve current behavior for the `showPresence = false` path (most call sites). Step 6 (impl) verifies no regression in MessageRow avatars + VoiceMemberRow + ChannelListItem.

## 13. Deltas from v1 (for review)

| Item | v1 | v2 |
|---|---|---|
| Email row in Account | Present with masking spec | **Removed** (UserSummary has no email — B1) |
| Avatar overlay geometry | Vague reference to `AvatarCore` | Explicit: outer Box not clipped, inner `AvatarCircle` helper extracted (B2) |
| Mic/deafen when Idle | "pending intent" via pendingSelfMute/Deaf flows | **Disabled icons** (M2 — drops complexity) |
| DaveLock | Slot pattern `@Composable () -> Unit` | **Move DaveLockIcon to commonMain**, drop slot (m1) |
| UserInfoRow params | 9 individual params | **UserInfoRowState data class + 3 callbacks** (m2) |
| Presence colors | Hardcoded hex 0xFF22C55E etc. | **Theme tokens** in PuklicColorScheme (M5) |
| Logout dialog copy | Lists "guild membership, channel state" | "Local message cache is kept" (M3) |
| PuklicAvatar ring | Hardcoded `colorScheme.surface` | **`ringColor` param** (M4) |
| Tests | 5 Compose UI tests claimed | **3 ViewModel tests**; UI tests deferred (m3) |
| openSettings default | `category = ACCOUNT` | `category = null` keeps last (n1) |
