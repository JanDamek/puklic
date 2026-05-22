package dev.puklic.domain

import kotlinx.datetime.Instant

data class MessageEmbed(
    val title: String?,
    val type: String?,
    val description: String?,
    val url: String?,
    val timestamp: Instant?,
    val color: Int?,
    val footer: EmbedFooter?,
    val image: EmbedMedia?,
    val thumbnail: EmbedMedia?,
    val video: EmbedMedia?,
    val provider: EmbedProvider?,
    val author: EmbedAuthor?,
    val fields: List<EmbedField>,
)

data class EmbedFooter(
    val text: String,
    val iconUrl: String?,
)

data class EmbedMedia(
    val url: String,
    val proxyUrl: String?,
    val width: Int?,
    val height: Int?,
)

data class EmbedProvider(
    val name: String?,
    val url: String?,
)

data class EmbedAuthor(
    val name: String,
    val url: String?,
    val iconUrl: String?,
)

data class EmbedField(
    val name: String,
    val value: String,
    val inline: Boolean,
)
