package dev.puklic.session.adapter

import dev.puklic.domain.ChannelType
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.PendingAttachmentUpload
import dev.puklic.repositories.MessageGateway
import dev.puklic.repositories.PendingAttachment

/**
 * Adapts the protocol-layer [DiscordMessageBridge] (domain-typed REST wrapper) to the
 * repositories-layer [MessageGateway] interface. Resolves the channel's actual Discord
 * type from [ChannelRepository] so that `X-Context-Properties.location_channel_type`
 * matches reality (text=0, announcement=5, …). Sending the wrong type causes Discord
 * to return 50001 Missing Access even for legitimately accessible channels.
 */
public class MessageGatewayAdapter(
    private val bridge: DiscordMessageBridge,
    private val channelRepository: ChannelRepository? = null,
) : MessageGateway {

    private suspend fun resolveGuildId(channelId: ChannelId): GuildId? =
        (channelRepository?.findById(channelId) as? GuildTextChannel)?.guildId

    private suspend fun resolveChannelType(channelId: ChannelId): Int =
        when (channelRepository?.findById(channelId)?.type) {
            ChannelType.GUILD_ANNOUNCEMENT -> DISCORD_TYPE_GUILD_ANNOUNCEMENT
            ChannelType.ANNOUNCEMENT_THREAD -> DISCORD_TYPE_ANNOUNCEMENT_THREAD
            ChannelType.PUBLIC_THREAD -> DISCORD_TYPE_PUBLIC_THREAD
            ChannelType.PRIVATE_THREAD -> DISCORD_TYPE_PRIVATE_THREAD
            ChannelType.GUILD_FORUM -> DISCORD_TYPE_GUILD_FORUM
            ChannelType.GUILD_MEDIA -> DISCORD_TYPE_GUILD_MEDIA
            else -> DISCORD_TYPE_GUILD_TEXT
        }

    override suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
        attachments: List<PendingAttachment>,
    ): Result<ChatMessage> = bridge.sendMessage(
        channelId = channelId,
        content = content,
        nonce = nonce,
        replyTo = replyTo,
        attachments = attachments.map { PendingAttachmentUpload(it.filename, it.bytes, it.contentType) },
    )

    override suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        newContent: String,
    ): Result<ChatMessage> = bridge.editMessage(channelId, messageId, newContent)

    override suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit> =
        bridge.deleteMessage(channelId, messageId)

    override suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        limit: Int,
    ): Result<List<ChatMessage>> = bridge.loadOlder(channelId, beforeId, limit)

    override suspend fun loadInitial(
        channelId: ChannelId,
        limit: Int,
    ): Result<List<ChatMessage>> = bridge.loadInitial(
        channelId = channelId,
        limit = limit,
        guildId = resolveGuildId(channelId),
        channelType = resolveChannelType(channelId),
    )

    override suspend fun addReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = bridge.addReaction(channelId, messageId, emoji)

    override suspend fun removeReaction(
        channelId: ChannelId,
        messageId: MessageId,
        emoji: EmojiRef,
    ): Result<Unit> = bridge.removeOwnReaction(channelId, messageId, emoji)

    private companion object {
        const val DISCORD_TYPE_GUILD_TEXT = 0
        const val DISCORD_TYPE_GUILD_ANNOUNCEMENT = 5
        const val DISCORD_TYPE_ANNOUNCEMENT_THREAD = 10
        const val DISCORD_TYPE_PUBLIC_THREAD = 11
        const val DISCORD_TYPE_PRIVATE_THREAD = 12
        const val DISCORD_TYPE_GUILD_FORUM = 15
        const val DISCORD_TYPE_GUILD_MEDIA = 16
    }
}
