package dev.puklic.repositories

import dev.puklic.domain.GuildChannel
import dev.puklic.domain.Member
import dev.puklic.domain.OverwriteType
import dev.puklic.domain.Role
import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId

/**
 * Pure permission calculator per the Discord spec, applied to issue #18 (hide channels without
 * VIEW_CHANNEL). See architect-report 2026-05-24-channel-permission-design.md §5.
 *
 * Algorithm: deny-then-allow overwrite ordering — @everyone overwrite, then role overwrites
 * (deny OR'd, allow OR'd), then member overwrite. Owner and ADMINISTRATOR bypass overwrites.
 *
 * The `everyoneRoleId` parameter encodes Discord's convention that the @everyone role id equals
 * the containing guild id. Callers pass it explicitly so this module never has to import
 * guild/role mappers.
 */
public object Permissions {
    public const val VIEW_CHANNEL: Long = 1L shl 10
    public const val ADMINISTRATOR: Long = 1L shl 3

    public fun canView(
        member: Member,
        channel: GuildChannel,
        rolesById: Map<RoleId, Role>,
        guildOwnerId: UserId,
        everyoneRoleId: RoleId,
    ): Boolean {
        if (member.userId == guildOwnerId) return true

        val everyone = rolesById[everyoneRoleId]
        val basePermissions = (everyone?.permissions ?: 0L) or
            member.roles.fold(0L) { acc, rid -> acc or (rolesById[rid]?.permissions ?: 0L) }
        if (basePermissions and ADMINISTRATOR != 0L) return true

        var permissions = basePermissions

        // 1. @everyone channel overwrite.
        val everyoneOverwrite = channel.permissionOverwrites.firstOrNull {
            it.type == OverwriteType.Role && it.targetId == everyoneRoleId.value
        }
        if (everyoneOverwrite != null) {
            permissions = (permissions and everyoneOverwrite.deny.inv()) or everyoneOverwrite.allow
        }

        // 2. Combined role overwrites (excluding @everyone).
        val memberRoleIds = member.roles.toSet()
        val roleOverwrites = channel.permissionOverwrites.filter {
            it.type == OverwriteType.Role &&
                it.targetId != everyoneRoleId.value &&
                RoleId(it.targetId) in memberRoleIds
        }
        if (roleOverwrites.isNotEmpty()) {
            val denyAll = roleOverwrites.fold(0L) { acc, ow -> acc or ow.deny }
            val allowAll = roleOverwrites.fold(0L) { acc, ow -> acc or ow.allow }
            permissions = (permissions and denyAll.inv()) or allowAll
        }

        // 3. Member-level overwrite.
        val memberOverwrite = channel.permissionOverwrites.firstOrNull {
            it.type == OverwriteType.Member && it.targetId == member.userId.value
        }
        if (memberOverwrite != null) {
            permissions = (permissions and memberOverwrite.deny.inv()) or memberOverwrite.allow
        }

        return permissions and VIEW_CHANNEL != 0L
    }

    /**
     * Permissive fallback variant — used during bootstrap when role / member data has not yet
     * arrived. Returns `true` (visible) on missing data; once data lands, the strict
     * [canView] applies.
     */
    public fun canViewSafe(
        member: Member?,
        channel: GuildChannel,
        roles: Map<RoleId, Role>,
        ownerId: UserId,
        everyoneRoleId: RoleId,
    ): Boolean {
        if (member == null) return true
        if (roles.isEmpty()) return true
        return canView(member, channel, roles, ownerId, everyoneRoleId)
    }

    /** Discord convention: the @everyone role id within a guild equals the guild's id. */
    public fun everyoneRoleId(guildId: GuildId): RoleId = RoleId(guildId.value)
}
