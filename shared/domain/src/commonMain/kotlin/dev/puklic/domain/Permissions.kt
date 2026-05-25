package dev.puklic.domain

import dev.puklic.ids.GuildId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant

/**
 * A Discord role within a single guild.
 *
 * Permissions are stored as the raw 64-bit bitmask Discord ships ("permissions" string field
 * parsed to Long). Only the bits actually consulted by [dev.puklic.repositories.Permissions]
 * (`VIEW_CHANNEL`, `ADMINISTRATOR`) matter for issue #18.
 */
public data class Role(
    val id: RoleId,
    val guildId: GuildId,
    val name: String,
    val permissions: Long,
    val position: Int,
)

/**
 * A guild member. v1 only tracks the **self** member (per architect-report
 * 2026-05-24-channel-permission-design.md §4) — other members are not stored, so the only
 * legitimate constructor caller is the gateway parser when it sees `user.id == selfUserId`.
 */
public data class Member(
    val userId: UserId,
    val guildId: GuildId,
    val roles: List<RoleId>,
    val nick: String? = null,
    val joinedAt: Instant? = null,
)

public enum class OverwriteType { Role, Member }

/**
 * Channel-level permission overwrite. `targetId` is either a [RoleId.value] or [UserId.value]
 * depending on [type]. `allow` and `deny` are 64-bit bitmasks matching Discord's spec.
 */
public data class PermissionOverwrite(
    val targetId: Long,
    val type: OverwriteType,
    val allow: Long,
    val deny: Long,
)
