# Channel Permission Filter — Design v2 (Step 2, 2026-05-25, issue #18)

> **v2 revision** addressing Step 3 critic findings: F1, F4, F5, F9, F11, F13, F14, F16, F17.
> Supersedes v1 (2026-05-24). Structure preserved; sections expanded/added inline.

## Summary

Issue #18 requires hiding channels the user cannot view. Step 1 confirmed greenfield — no Role/Member/PermissionOverwrite domain, no DTOs, no calculator, no `GUILD_MEMBER_UPDATE` handling. Design adds 3 domain types + matching DTOs + extends guild channels with overwrites list. State lives in 2 ephemeral `MutableStateFlow`-backed stores (`RoleStore`, `SelfMemberStore` — self only), justified by ~1 MB worst-case + READY-driven rebuild. Pure-commonMain `Permissions` calculator (~50 LOC) per Discord spec. Filter in `ChannelOrchestrator.channelsForGuild` with **two-stage memoized combine**. `NotificationDispatcher` gates on the same visibility check. Permissive fallback during bootstrap. UI hides empty categories.

## 1. Goal & non-goals

**Goal:** Filter `ChannelOrchestrator.channelsForGuild` so channels where user has no `VIEW_CHANNEL` are hidden, and suppress notifications for the same channels.

**Non-goals (v1):**
- Channel-position sort beyond existing `position`
- Per-channel sync state tracking
- Role/member management UI (events parsed, no UI surface)
- DM / Group DM channels (always visible — no permission concept)
- Non-VIEW permission inheritance (SEND_MESSAGES etc.)
- Persistent role/member cache (ephemeral; rebuilt from READY)
- Sequence-gap tombstones on RESUMED (documented, deferred — §10)

## 2. Domain types (NEW)

`shared/domain/src/commonMain/kotlin/dev/puklic/domain/`:

```kotlin
data class Role(
    val id: RoleId,
    val guildId: GuildId,
    val name: String,
    val permissions: Long,
    val position: Int,
)

data class Member(
    val userId: UserId,
    val guildId: GuildId,
    val roles: List<RoleId>,
    val nick: String? = null,
    val joinedAt: Instant? = null,
)

data class PermissionOverwrite(
    val targetId: Long,        // RoleId.value or UserId.value
    val type: OverwriteType,
    val allow: Long,
    val deny: Long,
)
enum class OverwriteType { Role, Member }
```

Add `permissionOverwrites: List<PermissionOverwrite> = emptyList()` to `GuildTextChannel`, `GuildVoiceChannel`, `GuildCategoryChannel`.

New value class: `RoleId(val value: Long)`.

## 3. DTO + mapper changes

```kotlin
@Serializable internal data class PermissionOverwriteDto(
    val id: String, val type: Int,
    val allow: String, val deny: String,
)

// ChannelDto — append:
@SerialName("permission_overwrites")
val permissionOverwrites: List<PermissionOverwriteDto>? = null,

@Serializable internal data class DiscordRoleDto(
    val id: String, val name: String,
    val permissions: String,   // decimal bitmask
    val position: Int,
)

@Serializable internal data class DiscordMemberDto(
    val user: DiscordUserDto?,
    val roles: List<String>,
    val nick: String? = null,
    @SerialName("joined_at") val joinedAt: String? = null,
)
```

### 3a. READY `merged_members` 2D shape (F11)

Discord's user-mode READY uses a **2D array index-aligned with `guilds[]`**: `merged_members[i]` is the list of preloaded members for `guilds[i]`. In Puklic user-mode we only care about the self member entry.

```kotlin
@Serializable
internal data class DiscordReadyDto(
    val guilds: List<DiscordGuildDto>,
    @SerialName("merged_members") val mergedMembers: List<List<DiscordMemberDto>>? = null,
    @SerialName("user") val selfUser: DiscordUserDto,
    // ... session_id, resume_gateway_url, etc.
)
```

Parser contract (in `ReadyMapper`):
- For each index `i` in `guilds`, pair `guilds[i]` with `mergedMembers?.getOrNull(i)?.firstOrNull { it.user?.id == selfUserId }`.
- If `mergedMembers` is `null` or the inner list lacks the self entry, fall back to `guilds[i].members.firstOrNull { it.user?.id == selfUserId }` (some payloads embed members inline).
- Result fed into `SelfMemberStore.upsertSelf(...)` per guild.

Discord payload sources:
- READY `guilds[].roles[]` + top-level `merged_members[i]` (self for user-mode)
- GUILD_CREATE `roles`, `members`
- GUILD_ROLE_CREATE/UPDATE/DELETE
- GUILD_MEMBER_UPDATE (filter `user.id == selfUserId`)

## 4. Storage strategy — ephemeral v1

**Decision: in-memory MutableStateFlow, no SQLDelight v1.**

Rationale: self roles per guild rarely change; READY rebuilds the entire state; ~100 guilds × ~50 roles × ~200 B ≈ 1 MB.

```kotlin
class RoleStore {
    private val rolesByGuild = MutableStateFlow<Map<GuildId, Map<RoleId, Role>>>(emptyMap())
    val state: StateFlow<Map<GuildId, Map<RoleId, Role>>> = rolesByGuild
    fun upsert(guildId: GuildId, roles: List<Role>) { ... }
    fun remove(guildId: GuildId, roleId: RoleId) { ... }
    fun replaceAll(snapshot: Map<GuildId, Map<RoleId, Role>>) { ... }  // READY rebuild
    fun rolesFor(guildId: GuildId) = state.value[guildId].orEmpty()
}

// F9: renamed from MemberStore — grep-discoverable, self-only intent is in the type name.
class SelfMemberStore {
    private val selfMembers = MutableStateFlow<Map<GuildId, Member>>(emptyMap())
    val state: StateFlow<Map<GuildId, Member>> = selfMembers
    fun upsertSelf(member: Member) { ... }
    fun replaceAll(snapshot: Map<GuildId, Member>) { ... }  // READY rebuild
    fun selfFor(guildId: GuildId) = state.value[guildId]
}
```

v2 follow-up: persist if memory pressure or restart-resilience needed.

## 5. Permissions calculator

`shared/repositories/src/commonMain/kotlin/dev/puklic/repositories/Permissions.kt` (~50 LOC pure Kotlin):
- Base = @everyone role permissions (role.id.value == guildId.value)
- Owner → all bits
- `ADMINISTRATOR` bit → all bits
- Per spec: @everyone overwrite → role overwrites (deny OR'd first, then allow OR'd) → member overwrite (deny then allow)
- Returns `Long`; `canView` checks `VIEW_CHANNEL` (`1L shl 10`)

## 6. Permissive fallback during bootstrap

```kotlin
fun canViewSafe(member: Member?, channel: GuildChannel,
                roles: Map<RoleId, Role>, ownerId: UserId): Boolean {
    if (member == null) return true       // member data not yet arrived
    if (roles.isEmpty()) return true      // role data not yet arrived
    return Permissions.canView(member, channel, roles, ownerId)
}
```

Brief flash as channels disappear when data arrives — acceptable, matches Discord client behaviour.

## 6a. NotificationDispatcher visibility gating (NEW — F4)

Cross-cutting concern: notifications must not bypass the visibility filter. A `MESSAGE_CREATE` arriving for a channel the user cannot view must not surface a system notification — even if the user is mentioned (mention from a role they cannot see is itself a leak signal).

```kotlin
// In NotificationDispatcher.handleMessageCreated
suspend fun handleMessageCreated(message: Message) {
    val visible = visibilityCheck.isChannelVisible(message.channelId) // suspending peek of latest visibility
        ?: return  // null = no visibility info yet → suppress until known (conservative)
    if (!visible) return
    // existing DM-or-mention logic continues below
}
```

`VisibilityCheck` is a thin port owned by `ChannelOrchestrator` exposing:

```kotlin
interface VisibilityCheck {
    /** Returns latest known visibility, or null if no data yet. Non-blocking peek. */
    fun isChannelVisible(channelId: ChannelId): Boolean?
}
```

Bootstrap policy: **conservative for notifications, permissive for UI** — UI shows channels until data lands (graceful flash); notifications stay silent until visibility is known (no leak risk). Two distinct trade-offs justified by different consequences (UI flash = annoying; phantom notification = privacy leak).

Test (in §11):
- "Mention in non-viewable channel suppressed"
- "Bootstrap with no visibility data — notification suppressed (returns null)"
- "Cancel notification once roles arrive and channel becomes non-viewable"

## 7. Category inheritance — VIEW only

Discord spec: `VIEW_CHANNEL` is NOT inherited from category to child for computation purposes — child channels have their own overwrites. Compute per channel independently. Edge case (everyone DENY on category, role ALLOW on child) → child visible.

## 8. Filter insertion point — two-stage combine (REWRITTEN, F5)

The filter lives in `ChannelOrchestrator.channelsForGuild`. To memoize the expensive permission calculation and avoid re-running it on every channel-list emission (e.g. when only `lastMessageId` updates), split into a `visibleIds` flow:

```kotlin
/**
 * Visibility seam — filters guild channels via [Permissions.canViewSafe].
 * Two-stage combine: visibility set is computed independently of channel
 * cosmetic updates (name, topic, lastMessageId) and memoized via
 * [distinctUntilChanged]; the outer combine only re-filters when either
 * the channel list or the visibility set changes.
 *
 * See architect-report 2026-05-24-channel-permission-design.md (v2, §8).
 */
fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> {
    val visibleIds: Flow<Set<ChannelId>> = combine(
        channelRepository.observeByGuild(guildId),
        roleStore.state,
        selfMemberStore.state,
        guildRepository.observeOwner(guildId),
    ) { channels, allRoles, allMembers, ownerId ->
        val member = allMembers[guildId]
        val roles = allRoles[guildId].orEmpty()
        channels.filterIsInstance<GuildChannel>()
            .filter { canViewSafe(member, it, roles, ownerId) }
            .map { it.id }
            .toSet()
    }.distinctUntilChanged()

    return channelRepository.observeByGuild(guildId).combine(visibleIds) { all, visible ->
        all.filter { it !is GuildChannel || it.id in visible }
    }
}
```

`ChannelOrchestrator` also exposes `VisibilityCheck` (see §6a) backed by a `StateFlow<Map<ChannelId, Boolean>>` collapsed across all guilds; updated by an internal collector on the per-guild visibility flows.

## 9. Hide empty categories

In `ChannelListPane`:
```kotlin
val visibleCategories = categories.filter { cat ->
    visibleChannels.any { it.parentId == cat.id }
}
```

## 10. Gateway event handling + RESUMED (EXPANDED, F1 + F14)

Extend `PublicApi.mapDispatch` + `GatewayDomainEvent`:

| Event | Action |
|---|---|
| `READY` | `RoleStore.replaceAll(...)` + `SelfMemberStore.replaceAll(...)` from `guilds[].roles` + `merged_members[i]` |
| `RESUMED` | No-op on stores; trust Discord's replay-in-order guarantee for the missed dispatches that follow |
| `GUILD_CREATE` | upsert roles + self member |
| `GUILD_DELETE` | drop guild from both stores |
| `GUILD_ROLE_CREATE` / `GUILD_ROLE_UPDATE` | `RoleStore.upsert` |
| `GUILD_ROLE_DELETE` | `RoleStore.remove` |
| `GUILD_MEMBER_UPDATE` | if `user.id == selfUserId` → `SelfMemberStore.upsertSelf` |
| `CHANNEL_UPDATE` | repository update; visibility re-emits via combine |
| Invalid Session | client re-IDENTIFYs → fresh READY → `replaceAll(...)` on both stores |

New `GatewayDomainEvent` subtypes: `RoleCreated`, `RoleUpdated`, `RoleDeleted`, `MemberUpdated`, `Resumed`.

### RESUMED contract

Per Discord docs, after a successful `RESUME` opcode the gateway **replays all missed dispatches in order from the sequence number we supplied**. Therefore:

- Ephemeral stores need no special handling on `RESUMED` — the subsequent `GUILD_ROLE_UPDATE` / `GUILD_MEMBER_UPDATE` events arrive and apply normally.
- The only risk is a dropped event during the disconnect window itself; this is bounded by Discord's server-side replay buffer.
- **Out of scope v1:** sequence-gap tombstone (force full re-IDENTIFY when `seq` gap exceeds threshold). Documented as v2 follow-up if observed in practice.

On Invalid Session opcode (server lost our session): client emits `IDENTIFY` and waits for a fresh `READY`; both stores are wiped via `replaceAll(emptyMap())` then refilled. Tests cover both paths (§11).

## 11. Tests (Step 5) — EXTENDED (F13, F14, F4)

`shared/repositories/src/commonTest/kotlin/.../PermissionsTest.kt`:
- @everyone VIEW → unroled user sees channel
- @everyone DENY VIEW → hidden absent allow
- Owner sees all
- `ADMINISTRATOR` bypasses overwrites
- Role allow overrides @everyone deny
- Member overwrite overrides role overwrite
- Deny-then-allow ordering per spec
- No overwrites + @everyone VIEW → visible
- Multiple roles: deny OR-combined, allow OR-combined
- `everyoneRoleId == guildId` convention

`GuildMapperTest.kt` (NEW + extended, F13):
- "parsed @everyone role ID equals guildId" (Discord convention assertion)
- "merged_members[i] pairs to guilds[i] by index"
- "merged_members shorter than guilds → trailing guilds get no self member (null)"
- "merged_members null → fall back to inline `guilds[i].members`"

`RoleStoreTest`, `SelfMemberStoreTest` (Turbine).

`ChannelOrchestratorPermissionTest` (Turbine):
- bootstrap permissive → all channels visible
- roles arrive → non-viewable filtered
- two-stage memoization: channel-name change does not re-run permission calc (assert via counter-spy on `Permissions.canView`)

`ChannelOrchestratorPermissionResumeTest` (NEW, F14):
- "RESUME replays `GUILD_ROLE_UPDATE` → `RoleStore.upsert` + visibility flow re-emits filtered list"
- "Invalid Session → re-IDENTIFY → `RoleStore.replaceAll` rebuilds from READY; previously visible channel correctly hidden if @everyone changed during outage"
- "`RESUMED` opcode itself is a no-op on stores (no spurious emissions)"

`NotificationDispatcherVisibilityTest` (NEW, F4):
- "Mention in non-viewable channel suppressed"
- "Bootstrap permissive — `VisibilityCheck` returns null → notification suppressed (conservative)"
- "Cancel pending notification once roles arrive and channel becomes non-viewable" (edge case — implementation may treat this as best-effort)
- "DM channel always notifies regardless of visibility check" (DM short-circuit)

## 12. Risks

1. Member roles late — permissive fallback (UI), conservative null (notifications)
2. Memory growth — ~1 MB; revisit at 1000+ guilds
3. Non-VIEW edge cases — out of scope v1
4. `GUILD_MEMBER_UPDATE` coverage — only self matters
5. `everyoneRoleId.value == guildId.value` convention — asserted in `GuildMapperTest`
6. RESUMED replay buffer overrun on Discord side (rare; deferred tombstone)
7. Notification false-suppression during long bootstrap (mitigated by Discord's fast READY)

## 13. Library survey (NEW — F16, per memory rule "Library-first before custom")

| Library | Coordinates | KMP | License | Pros | Cons | Verdict |
|---|---|---|---|---|---|---|
| Kord (full) | `dev.kord:kord-core:0.14.0` | partial (JVM primary; KMP planned) | MIT | Mature, used by major bots, `Permissions` class + bit ops included | Bot-oriented (relies on Gateway intents we don't use); large transitive deps (~5 MB); KMP support incomplete | REJECTED — bot assumptions + size |
| Kord common only | `dev.kord:kord-common:0.14.0` | JVM | MIT | Smaller subset; `Permissions` value class + bit calculations isolated | Still JVM-only; vendoring ~50 LOC is the same effort with full control | REJECTED — no KMP win |
| discord4j | `com.discord4j:discord4j-core:3.2.6` | JVM | Apache-2.0 | Reactive (Reactor); permission utility class exists | JVM-only, Reactor dep we don't need; bot-oriented | REJECTED — wrong paradigm |
| kordlib core source vendor | (manual copy of MIT-licensed file) | n/a | MIT | Hand-pick `Permissions` only | Maintenance — must track upstream changes; same LOC as writing fresh | REJECTED — same as hand-roll |
| dimensional / disko / KMP ports | (various) | KMP | various | KMP-native ambition | Mostly abandoned / experimental; no production users | REJECTED — production risk |
| **Hand-rolled** | n/a (commonMain) | yes | Apache-2.0 (project) | ~50 LOC pure Kotlin, full control, KMP-native, Discord permission spec is public + stable | Must implement deny-then-allow ordering correctly (covered by `PermissionsTest`) | **CHOSEN** |

**Justification:** The Discord permission spec is stable and the calculation is ~50 LOC of pure integer math. All KMP-native alternatives are abandoned or experimental. JVM-only options pull bot infrastructure (Gateway intents, REST clients, reactive runtimes) we don't use. Hand-rolling in `commonMain` is the minimum-complexity choice consistent with the "Library-first before custom" rule — the rule requires the survey, not adoption of an unfit library.

## 14. Open questions for Step 3 critic (v1 — now resolved)

- ~~Persist roles to SQLite v1 vs ephemeral~~ — ephemeral confirmed
- ~~Filter in orchestrator vs MainViewModel~~ — orchestrator confirmed (single seam, §8 KDoc)
- ~~DMs through filter?~~ — No, short-circuit on non-`GuildChannel`
- ~~Show channels with `MANAGE_CHANNELS` but no `VIEW`?~~ — `ADMINISTRATOR` bypass covers common cases; out of scope for `MANAGE_CHANNELS`-only roles

## 15. Changelog from v1

- §3a NEW — `merged_members` 2D array shape and parser contract (F11)
- §4 — `MemberStore` renamed `SelfMemberStore` (F9); `replaceAll` added for READY/Invalid-Session rebuild
- §6a NEW — `NotificationDispatcher` visibility gating + `VisibilityCheck` port (F4)
- §8 REWRITTEN — two-stage memoized combine; KDoc visibility-seam pointer (F5, F17)
- §10 EXPANDED — explicit `RESUMED` contract + Invalid Session path; tombstone deferred (F1)
- §11 EXTENDED — `GuildMapperTest` @everyone-id case, RESUME/Invalid-Session tests, NotificationDispatcher tests (F13, F14, F4)
- §13 NEW — explicit library survey table (F16)
