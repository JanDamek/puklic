# Channel Permission Filter — Step 1 Analysis (2026-05-24, issue #18)

## Summary

**Greenfield domain** — Puklic has ZERO permission infrastructure. The fix requires:
1. New domain types: `Role`, `Member`, `PermissionOverwrite`
2. DTO extensions: `DiscordChannelDto.permission_overwrites`, new `DiscordRoleDto`, `DiscordMemberDto`
3. Gateway events: `GUILD_MEMBER_UPDATE` (+ optional `GUILD_ROLE_*`)
4. Persistence: store member roles per guild (probably `member` table)
5. Pure hand-rolled permission calculator (~50 lines, Discord spec)
6. Filter Flow before `ChannelListPane`
7. UI: hide empty categories after filtering

**Library survey result:** Hand-rolled wins. Kord/Javacord are bot-oriented JVM-only — too heavy for KMP user client.

## Step 1 findings — gap matrix

| Artifact | Current state | Needed |
|---|---|---|
| `Role` domain class | DOES NOT EXIST | New `Role(id: RoleId, guildId: GuildId, name: String, permissions: Long, position: Int)` |
| `Member` domain class | DOES NOT EXIST | New `Member(userId, guildId, roles: List<RoleId>, nick?, joinedAt?)` |
| `PermissionOverwrite` domain | DOES NOT EXIST | New `PermissionOverwrite(id: SnowflakeId, type: OverwriteType, allow: Long, deny: Long)` |
| `GuildTextChannel.permissionOverwrites` | MISSING field | Add `permissionOverwrites: List<PermissionOverwrite> = emptyList()` |
| `GuildVoiceChannel`/`Category` overwrites | MISSING field | Same |
| `DiscordChannelDto.permission_overwrites` | MISSING | Add `@SerialName("permission_overwrites") val permissionOverwrites: List<PermissionOverwriteDto>?` |
| `DiscordRoleDto` | DOES NOT EXIST | New DTO for roles |
| `DiscordMemberDto` | DOES NOT EXIST | New DTO for members |
| `GUILD_MEMBER_UPDATE` event | NOT HANDLED | Add to `GatewayEventSource`, `PublicApi.mapDispatch` |
| Permission calculator | DOES NOT EXIST | New `Permissions.canView(member, channel, roles, ownerId): Boolean` (~50 lines) |
| Member role storage | DOES NOT EXIST | SQLite `member` table OR in-memory ephemeral |
| `Guild.ownerId` | ✅ EXISTS at Guild.kt:10 | Already there |
| `DiscordGuildDto.owner_id` | ✅ EXISTS at GuildDto.kt:15 | Already there |
| Channel list filter slot | ✅ AVAILABLE | `ChannelOrchestrator.channelsForGuild(guildId)` Flow at ChannelOrchestrator.kt:38-39 — wrap with filter |
| Category visibility post-filter | NOT IMPLEMENTED | After filtering channels, re-filter categories `cat → children.any { canView(it) }` |

## Library survey

| Library | Type | Verdict |
|---|---|---|
| **Kord** `dev.kord:kord-core` | JVM Kotlin bot framework | ❌ Too heavy (~50K LOC, bot-oriented, JVM-only, not KMP-clean) |
| **Javacord** `org.javacord:javacord` | JVM Java | ❌ Java-only, bot-oriented, not KMP |
| **Hand-rolled** | Pure Kotlin in commonMain | ✅ **RECOMMENDED** — ~50 lines, KMP-native, Discord spec is stable + public |

Reference: https://discord.com/developers/docs/topics/permissions

## Permission calculation algorithm (Discord spec)

```kotlin
object Permissions {
    const val VIEW_CHANNEL = 1L shl 10
    const val ADMINISTRATOR = 1L shl 3
    
    fun canView(
        member: Member,
        channel: GuildChannel,
        rolesById: Map<RoleId, Role>,
        guildOwnerId: UserId,
        everyoneRoleId: RoleId,  // = guildId.value as RoleId per Discord convention
    ): Boolean {
        // Owner sees all
        if (member.userId == guildOwnerId) return true
        
        // Compute base permissions = @everyone | (assigned roles OR-combined)
        val everyoneRole = rolesById[everyoneRoleId] ?: return true  // safe default
        var permissions = everyoneRole.permissions
        member.roles.forEach { roleId ->
            rolesById[roleId]?.let { permissions = permissions or it.permissions }
        }
        
        // ADMINISTRATOR bypasses overwrites
        if (permissions and ADMINISTRATOR != 0L) return true
        
        // Apply @everyone overwrite first
        channel.permissionOverwrites.firstOrNull { it.id.value == everyoneRoleId.value }?.let {
            permissions = permissions and it.deny.inv()
            permissions = permissions or it.allow
        }
        
        // Apply role overwrites — combined deny then combined allow per Discord spec
        var roleAllow = 0L
        var roleDeny = 0L
        channel.permissionOverwrites
            .filter { it.type == OverwriteType.Role && it.id != everyoneRoleId.value.toSnowflake() }
            .filter { it.id.toRoleId() in member.roles }
            .forEach {
                roleAllow = roleAllow or it.allow
                roleDeny = roleDeny or it.deny
            }
        permissions = permissions and roleDeny.inv()
        permissions = permissions or roleAllow
        
        // Apply member-specific overwrite
        channel.permissionOverwrites
            .firstOrNull { it.type == OverwriteType.Member && it.id.toUserId() == member.userId }
            ?.let {
                permissions = permissions and it.deny.inv()
                permissions = permissions or it.allow
            }
        
        return (permissions and VIEW_CHANNEL) != 0L
    }
}
```

## Insertion points

1. **Domain** — `shared/domain/src/commonMain/`:
   - `Role.kt` (new)
   - `Member.kt` (new)
   - `PermissionOverwrite.kt` (new)
   - `Channel.kt` — extend GuildTextChannel + others with `permissionOverwrites` field

2. **DTOs** — `shared/protocol-discord/src/commonMain/`:
   - `RoleDto.kt` (new)
   - `MemberDto.kt` (new)
   - `PermissionOverwriteDto.kt` (new)
   - `ChannelDto.kt` — add `permission_overwrites` field
   - `PublicApi.kt:mapDispatch` — handle GUILD_MEMBER_UPDATE

3. **Repository / orchestrator** — `shared/repositories/`:
   - `RoleStore` (new — guild → Map<RoleId, Role>)
   - `MemberStore` (new — guild → Member for self)
   - `Permissions.kt` (new — pure calculator)
   - `ChannelOrchestrator.channelsForGuild()` — wrap with `.map { it.filter { canView(...) } }`

4. **Persistence** — `shared/persistence-api/.../sqldelight/`:
   - `Role.sq` (new table OR embedded JSON in guild)
   - `Member.sq` (new — at minimum self's roles per guild)
   - `Channel.sq` — add `permission_overwrites_json TEXT` column (or separate table)

5. **UI** — `shared/compose-ui/`:
   - `ChannelListPane` — already iterates filtered list; no UI changes needed except hiding empty categories (~5 lines)

## Risks for Step 2

1. **Member roles arrive AFTER READY** — GUILD_MEMBER_UPDATE dispatches come later. Until then, list shows all channels (or hides all). Pick fallback: show all (permissive) vs hide all (conservative). Discord client uses permissive fallback briefly.
2. **GUILD_MEMBER_UPDATE not sent for all members** — only when something changes. Initial member info for self comes from MEMBER_LIST_UPDATE or READY's `merged_members` (user-mode only). Verify which payload carries it.
3. **`everyoneRoleId` convention** — Discord uses `role_id == guild_id` as @everyone marker. Verify in DiscordRoleDto.
4. **Large guilds** — hundreds of roles + thousands of overwrites. Filter runs on every emission. Memoize per (channelId, member.roles) hash if needed.
5. **`Channel.parent` permission inheritance** — Discord channels inherit parent category's overwrites unless overridden. Algorithm above does NOT handle inheritance. Verify spec — many sources say category permissions are computed separately. If inheritance is needed: compute parent permissions first, then apply channel-level overwrites.

## Next steps

Step 2 (architect design) covers:
- Decide ephemeral (in-memory) vs persistent (SQLite) for member/role storage
- Decide permissive vs conservative fallback before MEMBER data arrives
- Verify parent category permission inheritance
- Storage backing: separate tables vs embedded JSON
- Filter strategy: orchestrator transform vs ViewModel transform
