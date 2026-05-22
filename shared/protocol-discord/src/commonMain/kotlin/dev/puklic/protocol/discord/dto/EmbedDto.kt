package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordEmbedDto(
    val title: String? = null,
    val type: String? = null,
    val description: String? = null,
    val url: String? = null,
    val timestamp: String? = null,
    val color: Int? = null,
    val footer: DiscordEmbedFooterDto? = null,
    val image: DiscordEmbedMediaDto? = null,
    val thumbnail: DiscordEmbedMediaDto? = null,
    val video: DiscordEmbedMediaDto? = null,
    val provider: DiscordEmbedProviderDto? = null,
    val author: DiscordEmbedAuthorDto? = null,
    val fields: List<DiscordEmbedFieldDto> = emptyList(),
)

@Serializable
internal data class DiscordEmbedFooterDto(
    val text: String,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("proxy_icon_url") val proxyIconUrl: String? = null,
)

@Serializable
internal data class DiscordEmbedMediaDto(
    val url: String,
    @SerialName("proxy_url") val proxyUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class DiscordEmbedProviderDto(
    val name: String? = null,
    val url: String? = null,
)

@Serializable
internal data class DiscordEmbedAuthorDto(
    val name: String,
    val url: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("proxy_icon_url") val proxyIconUrl: String? = null,
)

@Serializable
internal data class DiscordEmbedFieldDto(
    val name: String,
    val value: String,
    val inline: Boolean = false,
)
