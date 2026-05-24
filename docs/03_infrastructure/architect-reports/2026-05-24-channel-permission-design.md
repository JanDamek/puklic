# Channel Permission Filter — Design (Step 2, 2026-05-24, issue #18)

## Summary

Issue #18 requires hiding channels the user can't view. Step 1 confirmed greenfield — no Role/Member/PermissionOverwrite domain, no DTOs, no calculator, no GUILD_MEMBER_UPDATE handling. Design adds 3 domain types + matching DTOs + extends guild channels with overwrites list. State lives in 2 ephemeral `MutableStateFlow`-backed stores (`RoleStore`, `MemberStore` self-only), justified by ~1 MB worst-case + READY-driven rebuild. Pure-commonMain `Permissions` calculator (~50 LOC) per Discord spec. Filter in `ChannelOrchestrator.channelsForGuild`. Permissive fallback during bootstrap. UI hides empty categories.

## 1. Goal & non-goals

**Goal:** Filter `ChannelOrchestrator.channelsForGuild` so channels where user has no VIEW_CHANNEL are hidden.

**Non-goals (v1):**
- Channel-position sort beyond existing `position`
- Per-channel sync state tracking
- Role/member management UI (events parsed, no UI surface)
- DM / Group DM channels (always visible — no permission concept)
- Non-VIEW permission inheritance (SEND_MESSAGES etc.)

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

Discord payload sources:
- READY `guilds[].roles[]` + `guilds[].merged_members[]` (self for user-mode)
- GUILD_CREATE `roles`, `members`
- GUILD_ROLE_CREATE/UPDATE/DELETE
- GUILD_MEMBER_UPDATE (filter `user.id == selfUserId`)

## 4. Storage strategy — ephemeral v1

**Decision: in-memory MutableStateFlow, no SQLDelight v1.**

Rationale: self roles per guild rarely changes; READY rebuilds; ~100 guilds × ~50 roles × ~200 B ≈ 1 MB.

```kotlin
class RoleStore {
    private val rolesByGuild = MutableStateFlow<Map<GuildId, Map<RoleId, Role>>>(emptyMap())
    val state: StateFlow<Map<GuildId, Map<RoleId, Role>>> = rolesByGuild
    fun upsert(guildId: GuildId, roles: List<Role>) { ... }
    fun remove(guildId: GuildId, roleId: RoleId) { ... }
    fun rolesFor(guildId: GuildId) = state.value[guildId].orEmpty()
}

class MemberStore {
    private val selfMembers = MutableStateFlow<Map<GuildId, Member>>(emptyMap())
    val state: StateFlow<Map<GuildId, Member>> = selfMembers
    fun upsertSelf(member: Member) { ... }
    fun selfFor(guildId: GuildId) = state.value[guildId]
}
```

v2 follow-up: persist if memory pressure or restart-resilience needed.

## 5. Permissions calculator

`shared/repositories/src/commonMain/kotlin/dev/puklic/repositories/Permissions.kt` (~50 LOC pure Kotlin):
- Base = @everyone role permissions (role.id.value == guildId.value)
- Owner → all bits
- ADMIN bit → all bits
- Per spec: @everyone overwrite → role overwrites (deny OR'd, allow OR'd) → member overwrite
- Returns Long; `canView` checks VIEW_CHANNEL (`1 shl 10`)

## 6. Permissive fallback during bootstrap

```kotlin
fun canViewSafe(member: Member?, channel: GuildChannel,
                roles: Map<RoleId, Role>, ownerId: UserId): Boolean {
    if (member == null) return true
    if (roles.isEmpty()) return true
    return Permissions.canView(member, channel, roles, ownerId)
}
```

Brief flash as channels disappear when data arrives — acceptable, matches Discord.

## 7. Category inheritance — VIEW only

Discord spec: VIEW_CHANNEL NOT inherited from category to child. Compute per channel independently. Edge case (everyone DENY on category, role ALLOW on child) → child visible.

## 8. Filter insertion point

`ChannelOrchestrator.channelsForGuild`:

```kotlin
fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> = combine(
    channelRepository.observeByGuild(guildId),
    roleStore.state,
    memberStore.state,
    guildRepository.observeOwner(guildId),
) { channels, allRoles, allMembers, ownerId ->
    val roles = allRoles[guildId].orEmpty()
    val member = allMembers[guildId]
    channels.filter {
        it !is GuildChannel || canViewSafe(member, it, roles, ownerId)
    }
}
```

## 9. Hide empty categories

In `ChannelListPane`:
```kotlin
val visibleCategories = categories.filter { cat ->
    visibleChannels.any { it.parentId == cat.id }
}
```

## 10. Gateway event handling

Extend `PublicApi.mapDispatch` + `GatewayDomainEvent`:

| Event | Action |
|---|---|
| READY | seed RoleStore + MemberStore from guilds[].roles + merged_members |
| GUILD_CREATE | upsert roles + self member |
| GUILD_ROLE_CREATE/UPDATE | RoleStore.upsert |
| GUILD_ROLE_DELETE | RoleStore.remove |
| GUILD_MEMBER_UPDATE | if user.id == selfUserId → MemberStore.upsertSelf |

New events: `RoleCreated`, `RoleUpdated`, `RoleDeleted`, `MemberUpdated`.

## 11. Tests (Step 5)

`PermissionsTest.kt`:
- @everyone VIEW → unroled user sees channel
- @everyone DENY VIEW → hidden absent allow
- Owner sees all
- ADMINISTRATOR bypasses overwrites
- Role allow overrides @everyone deny
- Member overwrite overrides role overwrite
- Deny-then-allow ordering per spec
- No overwrites + @everyone VIEW → visible
- Multiple roles: deny OR-combined, allow OR-combined
- `everyoneRoleId == guildId` convention

`RoleStoreTest`, `MemberStoreTest`, `ChannelOrchestratorPermissionTest` (Turbine).

## 12. Risks

1. Member roles late — permissive fallback
2. Memory growth — ~1 MB; revisit at 1000+ guilds
3. Non-VIEW edge cases — out of scope v1
4. GUILD_MEMBER_UPDATE coverage — only self matters
5. `everyoneRoleId.value == guildId.value` convention — assert in parser tests

## 13. Open questions for Step 3 critic

- Persist roles to SQLite v1 vs ephemeral (chose ephemeral)
- Filter in orchestrator vs MainViewModel (chose orchestrator)
- DMs through filter? (No — short-circuit on non-GuildChannel)
- Show channels with MANAGE_CHANNELS but no VIEW? (ADMINISTRATOR bypass handles common cases)
