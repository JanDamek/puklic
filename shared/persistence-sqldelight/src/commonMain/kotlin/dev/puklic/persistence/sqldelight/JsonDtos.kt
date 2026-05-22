package dev.puklic.persistence.sqldelight

import dev.puklic.domain.Attachment
import dev.puklic.domain.EmbedAuthor
import dev.puklic.domain.EmbedField
import dev.puklic.domain.EmbedFooter
import dev.puklic.domain.EmbedMedia
import dev.puklic.domain.EmbedProvider
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageEmbed
import dev.puklic.domain.Reaction
import dev.puklic.domain.ReactionCountDetails
import dev.puklic.ids.AttachmentId
import dev.puklic.ids.EmojiId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Persistence-internal DTOs for the JSON columns of the `message` table. These are NOT shared
 * with the protocol layer (Discord wire format is decoupled) and NOT with the domain layer
 * (`:shared:domain` types intentionally do not carry `@Serializable` annotations — see ADR-0006).
 *
 * Mappers between these DTOs and the public domain types live alongside in [MessageMappers].
 */
@Serializable
internal data class AttachmentDto(
    val id: Long,
    val filename: String,
    val size: Long,
    val url: String,
    val proxyUrl: String,
    val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSecs: Float? = null,
    val description: String? = null,
)

@Serializable
internal data class EmbedDto(
    val title: String? = null,
    val type: String? = null,
    val description: String? = null,
    val url: String? = null,
    val timestampEpochMs: Long? = null,
    val color: Int? = null,
    val footer: EmbedFooterDto? = null,
    val image: EmbedMediaDto? = null,
    val thumbnail: EmbedMediaDto? = null,
    val video: EmbedMediaDto? = null,
    val provider: EmbedProviderDto? = null,
    val author: EmbedAuthorDto? = null,
    val fields: List<EmbedFieldDto> = emptyList(),
)

@Serializable internal data class EmbedFooterDto(val text: String, val iconUrl: String? = null)
@Serializable internal data class EmbedMediaDto(
    val url: String,
    val proxyUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)
@Serializable internal data class EmbedProviderDto(val name: String? = null, val url: String? = null)
@Serializable internal data class EmbedAuthorDto(val name: String, val url: String? = null, val iconUrl: String? = null)
@Serializable internal data class EmbedFieldDto(val name: String, val value: String, val inline: Boolean)

@Serializable
internal data class ReactionDto(
    val emoji: EmojiRefDto,
    val count: Int,
    val me: Boolean,
    val countDetails: ReactionCountDetailsDto? = null,
)

@Serializable
internal sealed interface EmojiRefDto {
    @Serializable
    @kotlinx.serialization.SerialName("unicode")
    data class Unicode(val codepoint: String) : EmojiRefDto

    @Serializable
    @kotlinx.serialization.SerialName("custom")
    data class Custom(val id: Long, val name: String, val animated: Boolean) : EmojiRefDto
}

@Serializable internal data class ReactionCountDetailsDto(val burst: Int, val normal: Int)

// ── Mappers ────────────────────────────────────────────────────────────────

internal fun Attachment.toDto(): AttachmentDto = AttachmentDto(
    id = id.value,
    filename = filename,
    size = size,
    url = url,
    proxyUrl = proxyUrl,
    contentType = contentType,
    width = width,
    height = height,
    durationSecs = durationSecs,
    description = description,
)

internal fun AttachmentDto.toDomain(): Attachment = Attachment(
    id = AttachmentId(id),
    filename = filename,
    size = size,
    url = url,
    proxyUrl = proxyUrl,
    contentType = contentType,
    width = width,
    height = height,
    durationSecs = durationSecs,
    description = description,
)

internal fun MessageEmbed.toDto(): EmbedDto = EmbedDto(
    title = title,
    type = type,
    description = description,
    url = url,
    timestampEpochMs = timestamp?.toEpochMilliseconds(),
    color = color,
    footer = footer?.let { EmbedFooterDto(it.text, it.iconUrl) },
    image = image?.toDto(),
    thumbnail = thumbnail?.toDto(),
    video = video?.toDto(),
    provider = provider?.let { EmbedProviderDto(it.name, it.url) },
    author = author?.let { EmbedAuthorDto(it.name, it.url, it.iconUrl) },
    fields = fields.map { EmbedFieldDto(it.name, it.value, it.inline) },
)

internal fun EmbedDto.toDomain(): MessageEmbed = MessageEmbed(
    title = title,
    type = type,
    description = description,
    url = url,
    timestamp = timestampEpochMs?.let(Instant::fromEpochMilliseconds),
    color = color,
    footer = footer?.let { EmbedFooter(it.text, it.iconUrl) },
    image = image?.toDomain(),
    thumbnail = thumbnail?.toDomain(),
    video = video?.toDomain(),
    provider = provider?.let { EmbedProvider(it.name, it.url) },
    author = author?.let { EmbedAuthor(it.name, it.url, it.iconUrl) },
    fields = fields.map { EmbedField(it.name, it.value, it.inline) },
)

private fun EmbedMedia.toDto() = EmbedMediaDto(url, proxyUrl, width, height)
private fun EmbedMediaDto.toDomain() = EmbedMedia(url, proxyUrl, width, height)

internal fun Reaction.toDto(): ReactionDto = ReactionDto(
    emoji = when (val e = emoji) {
        is EmojiRef.Unicode -> EmojiRefDto.Unicode(e.codepoint)
        is EmojiRef.Custom -> EmojiRefDto.Custom(e.id.value, e.name, e.animated)
    },
    count = count,
    me = me,
    countDetails = countDetails?.let { ReactionCountDetailsDto(it.burst, it.normal) },
)

internal fun ReactionDto.toDomain(): Reaction = Reaction(
    emoji = when (val e = emoji) {
        is EmojiRefDto.Unicode -> EmojiRef.Unicode(e.codepoint)
        is EmojiRefDto.Custom -> EmojiRef.Custom(EmojiId(e.id), e.name, e.animated)
    },
    count = count,
    me = me,
    countDetails = countDetails?.let { ReactionCountDetails(it.burst, it.normal) },
)
