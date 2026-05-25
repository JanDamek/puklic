package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.Channel
import dev.puklic.domain.ChannelType
import dev.puklic.domain.DmChannel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildFeature
import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.domain.OverwriteType
import dev.puklic.domain.PermissionOverwrite
import dev.puklic.domain.Role
import dev.puklic.domain.Member
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import dev.puklic.protocol.discord.dto.DiscordGuildDto
import dev.puklic.protocol.discord.dto.DiscordMemberDto
import dev.puklic.protocol.discord.dto.DiscordRoleDto
import dev.puklic.protocol.discord.dto.PermissionOverwriteDto
import kotlinx.datetime.Instant

private const val CHANNEL_TYPE_GUILD_TEXT = 0
private const val CHANNEL_TYPE_DM = 1
private const val CHANNEL_TYPE_GROUP_DM = 3
private const val CHANNEL_TYPE_GUILD_CATEGORY = 4
private const val CHANNEL_TYPE_GUILD_ANNOUNCEMENT = 5
private const val CHANNEL_TYPE_GUILD_VOICE = 2
private const val CHANNEL_TYPE_GUILD_STAGE_VOICE = 13

/**
 * Returns null when the DTO is an "unavailable guild" stub (no resolvable name) — caller skips it.
 *
 * Discord ships two distinct guild payload shapes:
 *  - Bot-mode / REST: name, owner_id, icon, features live at the top level.
 *  - User-mode (web/desktop client gateway): the same fields are nested under `properties`.
 *
 * We resolve each field by preferring the top-level value and falling back to `properties`.
 */
internal fun DiscordGuildDto.toDomainOrNull(): Guild? {
    val resolvedName = name ?: properties?.name ?: return null
    return Guild(
        id = GuildId(id.toLong()),
        name = resolvedName,
        iconHash = icon ?: properties?.icon,
        ownerId = UserId((ownerId ?: properties?.ownerId ?: "0").toLong()),
        features = (features.ifEmpty { properties?.features.orEmpty() })
            .mapNotNull(::parseGuildFeature)
            .toSet(),
        memberCount = memberCount,
    )
}

internal fun DiscordGuildDto.toDomain(): Guild =
    Guild(
        id = GuildId(id.toLong()),
        name = name ?: properties?.name ?: "",
        iconHash = icon ?: properties?.icon,
        ownerId = UserId((ownerId ?: properties?.ownerId ?: "0").toLong()),
        features = (features.ifEmpty { properties?.features.orEmpty() })
            .mapNotNull(::parseGuildFeature)
            .toSet(),
        memberCount = memberCount,
    )

private fun parseGuildFeature(name: String): GuildFeature? =
    when (name) {
        "COMMUNITY" -> GuildFeature.COMMUNITY
        "VERIFIED" -> GuildFeature.VERIFIED
        "PARTNERED" -> GuildFeature.PARTNERED
        "DISCOVERABLE" -> GuildFeature.DISCOVERABLE
        else -> null
    }

internal fun DiscordChannelDto.toDomain(): Channel? {
    val overwrites = permissionOverwrites?.mapNotNull { it.toDomainOrNull() }.orEmpty()
    return when (type) {
        CHANNEL_TYPE_GUILD_TEXT, CHANNEL_TYPE_GUILD_ANNOUNCEMENT -> GuildTextChannel(
            id = ChannelId(id.toLong()),
            name = name,
            guildId = GuildId((guildId ?: "0").toLong()),
            parentId = parentId?.toLongOrNull()?.let(::ChannelId),
            topic = topic,
            position = position,
            rateLimitPerUser = rateLimitPerUser,
            nsfw = nsfw,
            permissionOverwrites = overwrites,
        )
        CHANNEL_TYPE_GUILD_VOICE, CHANNEL_TYPE_GUILD_STAGE_VOICE -> GuildVoiceChannel(
            id = ChannelId(id.toLong()),
            name = name,
            guildId = GuildId((guildId ?: "0").toLong()),
            position = position,
            parentId = parentId?.toLongOrNull()?.let(::ChannelId),
            bitrate = bitrate,
            userLimit = userLimit,
            permissionOverwrites = overwrites,
        )
        CHANNEL_TYPE_GUILD_CATEGORY -> GuildCategoryChannel(
            id = ChannelId(id.toLong()),
            name = name,
            guildId = GuildId((guildId ?: "0").toLong()),
            position = position,
            permissionOverwrites = overwrites,
        )
        CHANNEL_TYPE_DM, CHANNEL_TYPE_GROUP_DM -> DmChannel(
            id = ChannelId(id.toLong()),
            recipients = recipients.map { it.toDomain() },
            lastMessageId = lastMessageId?.toLongOrNull()?.let(::MessageId),
        )
        else -> null
    }
}

internal fun PermissionOverwriteDto.toDomainOrNull(): PermissionOverwrite? {
    val targetId = id.toLongOrNull() ?: return null
    val overwriteType = when (type) {
        0 -> OverwriteType.Role
        1 -> OverwriteType.Member
        else -> return null
    }
    val allowBits = allow.toLongOrNull() ?: 0L
    val denyBits = deny.toLongOrNull() ?: 0L
    return PermissionOverwrite(
        targetId = targetId,
        type = overwriteType,
        allow = allowBits,
        deny = denyBits,
    )
}

internal fun DiscordRoleDto.toDomain(guildId: GuildId): Role = Role(
    id = RoleId(id.toLong()),
    guildId = guildId,
    name = name,
    permissions = permissions.toLongOrNull() ?: 0L,
    position = position,
)

/** Maps a Discord member DTO to the domain [Member] for a specific guild. Requires [userId]
 *  because GUILD_MEMBER_UPDATE may carry the user payload inline rather than as a `user.id`. */
internal fun DiscordMemberDto.toDomain(guildId: GuildId, userId: UserId): Member = Member(
    userId = userId,
    guildId = guildId,
    roles = roles.mapNotNull { it.toLongOrNull()?.let(::RoleId) },
    nick = nick,
    joinedAt = joinedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
)

internal fun rawChannelType(type: Int): ChannelType? =
    when (type) {
        0 -> ChannelType.GUILD_TEXT
        1 -> ChannelType.DM
        2 -> ChannelType.GUILD_VOICE
        3 -> ChannelType.GROUP_DM
        4 -> ChannelType.GUILD_CATEGORY
        5 -> ChannelType.GUILD_ANNOUNCEMENT
        10 -> ChannelType.ANNOUNCEMENT_THREAD
        11 -> ChannelType.PUBLIC_THREAD
        12 -> ChannelType.PRIVATE_THREAD
        13 -> ChannelType.GUILD_STAGE_VOICE
        14 -> ChannelType.GUILD_DIRECTORY
        15 -> ChannelType.GUILD_FORUM
        16 -> ChannelType.GUILD_MEDIA
        else -> null
    }
