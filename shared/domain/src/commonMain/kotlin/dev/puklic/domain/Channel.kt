package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId

enum class ChannelType {
    GUILD_TEXT,
    DM,
    GUILD_VOICE,
    GROUP_DM,
    GUILD_CATEGORY,
    GUILD_ANNOUNCEMENT,
    ANNOUNCEMENT_THREAD,
    PUBLIC_THREAD,
    PRIVATE_THREAD,
    GUILD_STAGE_VOICE,
    GUILD_DIRECTORY,
    GUILD_FORUM,
    GUILD_MEDIA,
}

sealed interface Channel {
    val id: ChannelId
    val name: String?
    val type: ChannelType
}

/**
 * Marker for any channel that lives inside a guild and therefore participates in the
 * permission-filter pipeline (see architect-report 2026-05-24-channel-permission-design.md §2).
 *
 * The `permissionOverwrites` list mirrors Discord's `permission_overwrites` field on each
 * channel payload; it is empty when the gateway omits it (e.g. legacy payloads, or when no
 * overwrites are configured).
 */
sealed interface GuildChannel : Channel {
    val guildId: GuildId
    val position: Int
    val parentId: ChannelId?
    val permissionOverwrites: List<PermissionOverwrite>
}

data class GuildTextChannel(
    override val id: ChannelId,
    override val name: String?,
    override val guildId: GuildId,
    override val parentId: ChannelId?,
    val topic: String?,
    override val position: Int,
    val rateLimitPerUser: Int,
    val nsfw: Boolean,
    override val permissionOverwrites: List<PermissionOverwrite> = emptyList(),
) : GuildChannel {
    override val type: ChannelType = ChannelType.GUILD_TEXT
}

data class GuildVoiceChannel(
    override val id: ChannelId,
    override val name: String?,
    override val guildId: GuildId,
    override val position: Int,
    override val parentId: ChannelId?,
    val bitrate: Int?,
    val userLimit: Int?,
    override val permissionOverwrites: List<PermissionOverwrite> = emptyList(),
) : GuildChannel {
    override val type: ChannelType = ChannelType.GUILD_VOICE
}

data class GuildCategoryChannel(
    override val id: ChannelId,
    override val name: String?,
    override val guildId: GuildId,
    override val position: Int,
    override val parentId: ChannelId? = null,
    override val permissionOverwrites: List<PermissionOverwrite> = emptyList(),
) : GuildChannel {
    override val type: ChannelType = ChannelType.GUILD_CATEGORY
}

data class DmChannel(
    override val id: ChannelId,
    val recipients: List<UserSummary>,
    val lastMessageId: MessageId? = null,
) : Channel {
    override val name: String? = null
    override val type: ChannelType = ChannelType.DM
}
