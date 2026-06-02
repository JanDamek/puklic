package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.domain.Attachment
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.MessageFlags
import dev.puklic.domain.MessageMentions
import dev.puklic.domain.Reaction
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.UserSummary
import dev.puklic.ids.AttachmentId
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant

internal fun newInMemoryDatabase(): PuklicDatabase {
    val driver = JvmDriverFactory(JvmDriverFactory.DbPath.InMemory).createDriver()
    return PuklicDatabase(driver)
}

/**
 * Seed the user + channel + guild rows that all messages reference. Inserting a message without
 * these breaks the FK chain (we intentionally enforce `foreign_keys=ON`).
 */
internal fun PuklicDatabase.seedGuildChannelUser(
    guildId: GuildId = GuildId(100L),
    channelId: ChannelId = ChannelId(200L),
    userId: UserId = UserId(300L),
) {
    guildQueries.upsert(
        id = guildId.value,
        name = "test-guild",
        icon_hash = null,
        owner_id = userId.value,
        features = "[]",
        member_count = 1L,
        updated_at = 0L,
    )
    channelQueries.upsert(
        id = channelId.value,
        guild_id = guildId.value,
        parent_id = null,
        type = 0L,
        name = "general",
        topic = null,
        position = 0L,
        rate_limit_per_user = 0L,
        nsfw = 0L,
        bitrate = null,
        user_limit = null,
        last_message_id = null,
        updated_at = 0L,
        permission_overwrites_json = null,
    )
    userQueries.upsert(
        id = userId.value,
        username = "alice",
        global_name = null,
        discriminator = null,
        avatar_hash = null,
        bot = 0L,
        system = 0L,
        updated_at = 0L,
    )
}

internal fun newMessage(
    id: Long,
    channelId: ChannelId = ChannelId(200L),
    userId: UserId = UserId(300L),
    timestampMs: Long = id * 1000L,
    rawContent: String = "msg-$id",
    attachments: List<Attachment> = emptyList(),
    reactions: List<Reaction> = emptyList(),
): ChatMessage = ChatMessage(
    id = MessageId(id),
    channelId = channelId,
    author = UserSummary(userId, "alice", null, null, null, bot = false, system = false),
    rawContent = rawContent,
    parsedContent = RichTextDocument(emptyList()),
    attachments = attachments,
    embeds = emptyList(),
    reactions = reactions,
    mentions = MessageMentions(emptyList(), emptyList(), emptyList(), everyone = false),
    flags = MessageFlags.NONE,
    timestamp = Instant.fromEpochMilliseconds(timestampMs),
    editedTimestamp = null,
    referencedMessage = null,
)

internal fun sampleAttachment(id: Long = 1L) = Attachment(
    id = AttachmentId(id),
    filename = "file.png",
    size = 1024L,
    url = "https://cdn.discord.com/$id.png",
    proxyUrl = "https://media.discord.net/$id.png",
    contentType = "image/png",
    width = 100,
    height = 100,
    durationSecs = null,
    description = "alt-text",
)
