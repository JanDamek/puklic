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
    val type: MessageType = MessageType.DEFAULT,
    val timestamp: Instant,
    val editedTimestamp: Instant?,
    val referencedMessage: MessageReference?,
)

/**
 * Discord message type (raw numeric code mapped to a closed enum). Only the variants that the
 * client currently renders are listed explicitly; everything else lands on [UNKNOWN] and is
 * still shown as plain text so unknown variants never produce a blank row.
 *
 * Reference: https://discord.com/developers/docs/resources/channel#message-object-message-types
 */
enum class MessageType(val raw: Int) {
    DEFAULT(0),
    RECIPIENT_ADD(1),
    RECIPIENT_REMOVE(2),
    CALL(3),
    CHANNEL_NAME_CHANGE(4),
    CHANNEL_ICON_CHANGE(5),
    CHANNEL_PINNED_MESSAGE(6),
    USER_JOIN(7),
    REPLY(19),
    UNKNOWN(-1),
    ;

    public companion object {
        public fun fromRaw(raw: Int): MessageType =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

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
