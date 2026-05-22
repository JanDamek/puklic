package dev.puklic.session.adapter

import dev.puklic.domain.ChatMessage
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ChannelRepository
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.repositories.MessageGateway

/**
 * Adapts the protocol-layer [DiscordMessageBridge] (domain-typed REST wrapper) to the
 * repositories-layer [MessageGateway] interface consumed by [dev.puklic.repositories.MessageOrchestrator]
 * and [dev.puklic.repositories.OutboundMessageWorker]. Pure delegation — no mapping logic here.
 */
public class MessageGatewayAdapter(
    private val bridge: DiscordMessageBridge,
    private val channelRepository: ChannelRepository? = null,
) : MessageGateway {

    private suspend fun resolveGuildId(channelId: ChannelId): GuildId? =
        (channelRepository?.findById(channelId) as? GuildTextChannel)?.guildId

    override suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
    ): Result<ChatMessage> = bridge.sendMessage(channelId, content, nonce, replyTo)

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
    ): Result<List<ChatMessage>> = bridge.loadInitial(channelId, limit, resolveGuildId(channelId))
}
