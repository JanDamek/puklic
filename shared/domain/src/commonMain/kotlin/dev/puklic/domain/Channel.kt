package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId

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

data class GuildTextChannel(
    override val id: ChannelId,
    override val name: String?,
    val guildId: GuildId,
    val parentId: ChannelId?,
    val topic: String?,
    val position: Int,
    val rateLimitPerUser: Int,
    val nsfw: Boolean,
) : Channel {
    override val type: ChannelType = ChannelType.GUILD_TEXT
}

data class GuildCategoryChannel(
    override val id: ChannelId,
    override val name: String?,
    val guildId: GuildId,
    val position: Int,
) : Channel {
    override val type: ChannelType = ChannelType.GUILD_CATEGORY
}

data class DmChannel(
    override val id: ChannelId,
    val recipients: List<UserSummary>,
) : Channel {
    override val name: String? = null
    override val type: ChannelType = ChannelType.DM
}
