package dev.puklic.protocol.discord.mapper

import dev.puklic.domain.Channel
import dev.puklic.domain.ChannelType
import dev.puklic.domain.DmChannel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildFeature
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.dto.DiscordChannelDto
import dev.puklic.protocol.discord.dto.DiscordGuildDto

private const val CHANNEL_TYPE_GUILD_TEXT = 0
private const val CHANNEL_TYPE_DM = 1
private const val CHANNEL_TYPE_GROUP_DM = 3
private const val CHANNEL_TYPE_GUILD_CATEGORY = 4

/** Returns null when the DTO is an "unavailable guild" stub (no name) — caller skips it. */
internal fun DiscordGuildDto.toDomainOrNull(): Guild? {
    val resolvedName = name ?: return null
    return Guild(
        id = GuildId(id.toLong()),
        name = resolvedName,
        iconHash = icon,
        ownerId = UserId((ownerId ?: "0").toLong()),
        features = features.mapNotNull(::parseGuildFeature).toSet(),
        memberCount = memberCount,
    )
}

internal fun DiscordGuildDto.toDomain(): Guild =
    Guild(
        id = GuildId(id.toLong()),
        name = name ?: "",
        iconHash = icon,
        ownerId = UserId((ownerId ?: "0").toLong()),
        features = features.mapNotNull(::parseGuildFeature).toSet(),
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

internal fun DiscordChannelDto.toDomain(): Channel? =
    when (type) {
        CHANNEL_TYPE_GUILD_TEXT, CHANNEL_TYPE_GUILD_CATEGORY -> GuildTextChannel(
            id = ChannelId(id.toLong()),
            name = name,
            guildId = GuildId((guildId ?: "0").toLong()),
            parentId = parentId?.toLongOrNull()?.let(::ChannelId),
            topic = topic,
            position = position,
            rateLimitPerUser = rateLimitPerUser,
            nsfw = nsfw,
        )
        CHANNEL_TYPE_DM, CHANNEL_TYPE_GROUP_DM -> DmChannel(
            id = ChannelId(id.toLong()),
            recipients = recipients.map { it.toDomain() },
        )
        else -> null
    }

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
