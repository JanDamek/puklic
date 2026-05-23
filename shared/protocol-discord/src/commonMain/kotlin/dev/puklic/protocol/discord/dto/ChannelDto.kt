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
    val bitrate: Int? = null,
    @SerialName("user_limit") val userLimit: Int? = null,
    // Bot-mode + Group-DM payloads typically populate `recipients` with full user objects.
    val recipients: List<DiscordUserDto> = emptyList(),
    // User-mode (web/desktop client) READY DM channels carry only `recipient_ids`; the actual
    // user records live in the top-level `users` array and must be joined by the caller.
    @SerialName("recipient_ids") val recipientIds: List<String> = emptyList(),
    @SerialName("last_message_id") val lastMessageId: String? = null,
)
