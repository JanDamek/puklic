package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordChannelDto(
    val id: String,
    val type: Int,
    @SerialName("guild_id") val guildId: String? = null,
    val name: String? = null,
    val topic: String? = null,
    val nsfw: Boolean = false,
    val position: Int = 0,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("rate_limit_per_user") val rateLimitPerUser: Int = 0,
    val recipients: List<DiscordUserDto> = emptyList(),
    @SerialName("last_message_id") val lastMessageId: String? = null,
)
