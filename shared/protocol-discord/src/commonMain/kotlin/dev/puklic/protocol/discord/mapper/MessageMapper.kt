package dev.puklic.protocol.discord.mapper

import dev.puklic.chatparser.parseRichText
import dev.puklic.domain.Attachment
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmbedAuthor
import dev.puklic.domain.EmbedField
import dev.puklic.domain.EmbedFooter
import dev.puklic.domain.EmbedMedia
import dev.puklic.domain.EmbedProvider
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageEmbed
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.MessageReference
import dev.puklic.domain.Reaction
import dev.puklic.domain.ReactionCountDetails
import dev.puklic.domain.ReferenceType
import dev.puklic.ids.AttachmentId
import dev.puklic.ids.ChannelId
import dev.puklic.ids.EmojiId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.dto.DiscordAttachmentDto
import dev.puklic.protocol.discord.dto.DiscordEmbedDto
import dev.puklic.protocol.discord.dto.DiscordEmojiRefDto
import dev.puklic.protocol.discord.dto.DiscordMessageDto
import dev.puklic.protocol.discord.dto.DiscordMessageReferenceDto
import dev.puklic.protocol.discord.dto.DiscordReactionDto
import kotlinx.datetime.Instant

private const val FLAG_SUPPRESS_EMBEDS = 1 shl 2
private const val FLAG_EPHEMERAL = 1 shl 6
private const val MESSAGE_TYPE_REPLY = 19
private const val REFERENCE_TYPE_FORWARD = 1

internal fun DiscordMessageDto.toDomain(): ChatMessage =
    ChatMessage(
        id = MessageId(id.toLong()),
        channelId = ChannelId(channelId.toLong()),
        author = author.toDomain(),
        rawContent = content,
        parsedContent = parseRichText(content),
        attachments = attachments.map(DiscordAttachmentDto::toDomain),
        embeds = embeds.map(DiscordEmbedDto::toDomain),
        reactions = reactions.map(DiscordReactionDto::toDomain),
        mentions = MessageMentions(
            users = mentions.map { UserId(it.id.toLong()) },
            roles = mentionRoles.map { RoleId(it.toLong()) },
            channels = mentionChannels.map { ChannelId(it.id.toLong()) },
            everyone = mentionEveryone,
        ),
        flags = decodeFlags(flags, pinned, tts),
        timestamp = Instant.parse(timestamp),
        editedTimestamp = editedTimestamp?.let(Instant::parse),
        referencedMessage = messageReference?.toDomain(),
    )

private fun decodeFlags(flags: Int, pinned: Boolean, tts: Boolean): MessageFlags =
    when {
        flags and FLAG_EPHEMERAL != 0 -> MessageFlags.EPHEMERAL
        flags and FLAG_SUPPRESS_EMBEDS != 0 -> MessageFlags.SUPPRESS_EMBEDS
        pinned -> MessageFlags.PINNED
        tts -> MessageFlags.TTS
        else -> MessageFlags.NONE
    }

internal fun DiscordAttachmentDto.toDomain(): Attachment =
    Attachment(
        id = AttachmentId(id.toLong()),
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

internal fun DiscordReactionDto.toDomain(): Reaction =
    Reaction(
        emoji = emoji.toDomain(),
        count = count,
        me = me,
        countDetails = countDetails?.let { ReactionCountDetails(burst = it.burst, normal = it.normal) },
    )

internal fun DiscordEmojiRefDto.toDomain(): EmojiRef {
    val emojiId = id
    return if (emojiId != null) {
        EmojiRef.Custom(id = EmojiId(emojiId.toLong()), name = name ?: "", animated = animated)
    } else {
        EmojiRef.Unicode(codepoint = name ?: "")
    }
}

internal fun DiscordEmbedDto.toDomain(): MessageEmbed =
    MessageEmbed(
        title = title,
        type = type,
        description = description,
        url = url,
        timestamp = timestamp?.let(Instant::parse),
        color = color,
        footer = footer?.let { EmbedFooter(text = it.text, iconUrl = it.iconUrl) },
        image = image?.let { EmbedMedia(url = it.url, proxyUrl = it.proxyUrl, width = it.width, height = it.height) },
        thumbnail = thumbnail?.let {
            EmbedMedia(url = it.url, proxyUrl = it.proxyUrl, width = it.width, height = it.height)
        },
        video = video?.let { EmbedMedia(url = it.url, proxyUrl = it.proxyUrl, width = it.width, height = it.height) },
        provider = provider?.let { EmbedProvider(name = it.name, url = it.url) },
        author = author?.let { EmbedAuthor(name = it.name, url = it.url, iconUrl = it.iconUrl) },
        fields = fields.map { EmbedField(name = it.name, value = it.value, inline = it.inline) },
    )

internal fun DiscordMessageReferenceDto.toDomain(): MessageReference =
    MessageReference(
        messageId = messageId?.toLongOrNull()?.let(::MessageId),
        channelId = channelId?.toLongOrNull()?.let(::ChannelId),
        guildId = guildId?.toLongOrNull()?.let(::GuildId),
        type = if (type == REFERENCE_TYPE_FORWARD) ReferenceType.FORWARD else ReferenceType.REPLY,
    )
