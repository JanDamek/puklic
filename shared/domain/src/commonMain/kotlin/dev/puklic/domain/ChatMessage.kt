package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import kotlinx.datetime.Instant

data class ChatMessage(
    val id: MessageId,
    val channelId: ChannelId,
    val author: UserSummary,
    val rawContent: String,
    val parsedContent: RichTextDocument,
    val attachments: List<Attachment>,
    val embeds: List<MessageEmbed>,
    val reactions: List<Reaction>,
    val mentions: MessageMentions,
    val flags: MessageFlags,
    val timestamp: Instant,
    val editedTimestamp: Instant?,
    val referencedMessage: MessageReference?,
)

enum class MessageFlags {
    NONE,
    PINNED,
    EPHEMERAL,
    SUPPRESS_EMBEDS,
    TTS,
}

data class MessageReference(
    val messageId: MessageId?,
    val channelId: ChannelId?,
    val guildId: GuildId?,
    val type: ReferenceType,
)

enum class ReferenceType {
    REPLY,
    FORWARD,
}
