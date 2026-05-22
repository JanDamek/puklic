package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordMessageDto(
    val id: String,
    @SerialName("channel_id") val channelId: String,
    @SerialName("guild_id") val guildId: String? = null,
    val author: DiscordUserDto,
    val content: String = "",
    val timestamp: String,
    @SerialName("edited_timestamp") val editedTimestamp: String? = null,
    val tts: Boolean = false,
    val pinned: Boolean = false,
    val attachments: List<DiscordAttachmentDto> = emptyList(),
    val embeds: List<DiscordEmbedDto> = emptyList(),
    val reactions: List<DiscordReactionDto> = emptyList(),
    val mentions: List<DiscordUserDto> = emptyList(),
    @SerialName("mention_roles") val mentionRoles: List<String> = emptyList(),
    @SerialName("mention_channels") val mentionChannels: List<DiscordChannelMentionDto> = emptyList(),
    @SerialName("mention_everyone") val mentionEveryone: Boolean = false,
    val flags: Int = 0,
    val type: Int = 0,
    val nonce: String? = null,
    @SerialName("message_reference") val messageReference: DiscordMessageReferenceDto? = null,
    @SerialName("referenced_message") val referencedMessage: DiscordMessageDto? = null,
)

@Serializable
internal data class DiscordAttachmentDto(
    val id: String,
    val filename: String,
    val size: Long,
    val url: String,
    @SerialName("proxy_url") val proxyUrl: String,
    @SerialName("content_type") val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("duration_secs") val durationSecs: Float? = null,
    val description: String? = null,
)

@Serializable
internal data class DiscordReactionDto(
    val count: Int,
    @SerialName("count_details") val countDetails: DiscordReactionCountDetailsDto? = null,
    val me: Boolean = false,
    val emoji: DiscordEmojiRefDto,
)

@Serializable
internal data class DiscordReactionCountDetailsDto(
    val burst: Int = 0,
    val normal: Int = 0,
)

@Serializable
internal data class DiscordEmojiRefDto(
    val id: String? = null,
    val name: String? = null,
    val animated: Boolean = false,
)

@Serializable
internal data class DiscordMessageReferenceDto(
    val type: Int? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("guild_id") val guildId: String? = null,
)

@Serializable
internal data class DiscordChannelMentionDto(
    val id: String,
    @SerialName("guild_id") val guildId: String? = null,
    val type: Int,
    val name: String,
)
