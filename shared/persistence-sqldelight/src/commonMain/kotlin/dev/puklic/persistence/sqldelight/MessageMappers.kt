package dev.puklic.persistence.sqldelight

import dev.puklic.chatparser.parseRichText
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.MessageReference
import dev.puklic.domain.MessageType
import dev.puklic.domain.ReferenceType
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.UserSummary
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import dev.puklic.db.Message as MessageRow
import dev.puklic.db.User as UserRow

private val AttachmentListSerializer = ListSerializer(AttachmentDto.serializer())
private val EmbedListSerializer = ListSerializer(EmbedDto.serializer())
private val ReactionListSerializer = ListSerializer(ReactionDto.serializer())
private val LongListSerializer = ListSerializer(Long.serializer())

/**
 * Convert a [MessageRow] (SQLDelight-generated row type) plus its already-loaded author into a
 * domain [ChatMessage]. The caller is responsible for resolving the author from the user cache;
 * the row alone carries only `author_id`.
 *
 * `parsedContent` is produced inline by [parseRichText] over [raw_content]. The parser is a
 * pure, allocation-light function (no IO, no platform calls), so re-parsing on read is cheap
 * and avoids storing duplicate AST blobs in the DB. Mention / emoji label resolution still
 * happens in the UI layer via the `LocalMentionResolver` / `LocalEmojiResolver` composables.
 */
internal fun MessageRow.toDomain(author: UserSummary): ChatMessage = ChatMessage(
    id = MessageId(id),
    channelId = ChannelId(channel_id),
    author = author,
    rawContent = raw_content,
    parsedContent = if (raw_content.isBlank()) RichTextDocument(emptyList()) else parseRichText(raw_content),
    attachments = PersistenceJson.decodeFromString(AttachmentListSerializer, attachments_json)
        .map { it.toDomain() },
    embeds = PersistenceJson.decodeFromString(EmbedListSerializer, embeds_json)
        .map { it.toDomain() },
    reactions = PersistenceJson.decodeFromString(ReactionListSerializer, reactions_json)
        .map { it.toDomain() },
    mentions = MessageMentions(
        users = PersistenceJson.decodeFromString(LongListSerializer, mentions_users_json).map(::UserId),
        roles = PersistenceJson.decodeFromString(LongListSerializer, mentions_roles_json).map(::RoleId),
        channels = PersistenceJson.decodeFromString(LongListSerializer, mentions_channels_json).map(::ChannelId),
        everyone = (mentions_everyone ?: 0L) != 0L,
    ),
    flags = decodeFlags(flags),
    type = MessageType.fromRaw(type.toInt()),
    timestamp = Instant.fromEpochMilliseconds(timestamp),
    editedTimestamp = edited_timestamp?.let(Instant::fromEpochMilliseconds),
    referencedMessage = reference_type?.let { refType ->
        MessageReference(
            messageId = reference_message_id?.let(::MessageId),
            channelId = reference_channel_id?.let(::ChannelId),
            guildId = null,
            type = decodeReferenceType(refType),
        )
    },
)

internal data class MessageColumns(
    val id: Long,
    val channelId: Long,
    val authorId: Long,
    val rawContent: String,
    val timestamp: Long,
    val editedTimestamp: Long?,
    val flags: Long,
    val type: Long,
    val referenceMessageId: Long?,
    val referenceChannelId: Long?,
    val referenceType: Long?,
    val mentionsEveryone: Long?,
    val attachmentsJson: String,
    val embedsJson: String,
    val reactionsJson: String,
    val mentionsUsersJson: String,
    val mentionsRolesJson: String,
    val mentionsChannelsJson: String,
)

internal fun ChatMessage.toColumns(): MessageColumns = MessageColumns(
    id = id.value,
    channelId = channelId.value,
    authorId = author.id.value,
    rawContent = rawContent,
    timestamp = timestamp.toEpochMilliseconds(),
    editedTimestamp = editedTimestamp?.toEpochMilliseconds(),
    flags = encodeFlags(flags),
    type = type.raw.toLong(),
    referenceMessageId = referencedMessage?.messageId?.value,
    referenceChannelId = referencedMessage?.channelId?.value,
    referenceType = referencedMessage?.let { encodeReferenceType(it.type) },
    mentionsEveryone = if (mentions.everyone) 1L else 0L,
    attachmentsJson = PersistenceJson.encodeToString(
        AttachmentListSerializer,
        attachments.map { it.toDto() },
    ),
    embedsJson = PersistenceJson.encodeToString(
        EmbedListSerializer,
        embeds.map { it.toDto() },
    ),
    reactionsJson = PersistenceJson.encodeToString(
        ReactionListSerializer,
        reactions.map { it.toDto() },
    ),
    mentionsUsersJson = PersistenceJson.encodeToString(
        LongListSerializer,
        mentions.users.map { it.value },
    ),
    mentionsRolesJson = PersistenceJson.encodeToString(
        LongListSerializer,
        mentions.roles.map { it.value },
    ),
    mentionsChannelsJson = PersistenceJson.encodeToString(
        LongListSerializer,
        mentions.channels.map { it.value },
    ),
)

// MessageFlags is currently a flat enum; encode as ordinal. When promoted to a bitmask (per
// Discord API), update both directions and add a migration test.
private fun encodeFlags(flags: MessageFlags): Long = flags.ordinal.toLong()
private fun decodeFlags(value: Long): MessageFlags =
    MessageFlags.entries.getOrElse(value.toInt()) { MessageFlags.NONE }

private const val REFERENCE_TYPE_REPLY = 0L
private const val REFERENCE_TYPE_FORWARD = 1L

private fun encodeReferenceType(type: ReferenceType): Long = when (type) {
    ReferenceType.REPLY -> REFERENCE_TYPE_REPLY
    ReferenceType.FORWARD -> REFERENCE_TYPE_FORWARD
}

private fun decodeReferenceType(value: Long): ReferenceType = when (value) {
    REFERENCE_TYPE_REPLY -> ReferenceType.REPLY
    REFERENCE_TYPE_FORWARD -> ReferenceType.FORWARD
    else -> error("Unknown reference_type=$value in persisted row")
}

internal fun UserRow.toDomain(): UserSummary = UserSummary(
    id = UserId(id),
    username = username,
    globalName = global_name,
    discriminator = discriminator,
    avatarHash = avatar_hash,
    bot = (bot ?: 0L) != 0L,
    system = (system ?: 0L) != 0L,
)

internal fun UserSummary.toRow(updatedAtEpochMs: Long): UserRow = UserRow(
    id = id.value,
    username = username,
    global_name = globalName,
    discriminator = discriminator,
    avatar_hash = avatarHash,
    bot = if (bot) 1L else 0L,
    system = if (system) 1L else 0L,
    updated_at = updatedAtEpochMs,
)

// Suppress unused warning until GuildId references are added by higher layers.
@Suppress("unused")
private val keepGuildIdImportLive: GuildId? = null
