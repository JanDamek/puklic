package dev.puklic.repositories

import dev.puklic.domain.ChatMessage
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId

/**
 * Domain-level abstraction over the Discord REST surface needed by [MessageOrchestrator].
 *
 * The protocol-discord REST client uses `internal` DTOs; the wiring layer (later, Step 15-16)
 * supplies the implementation that maps DTOs to domain types. Tests use in-memory fakes.
 */
public interface MessageGateway {
    /** Send a new message. On success returns the server's authoritative [ChatMessage]. */
    public suspend fun sendMessage(
        channelId: ChannelId,
        content: String,
        nonce: String,
        replyTo: MessageId?,
    ): Result<ChatMessage>

    /** Edit a message. Returns the updated [ChatMessage] on success. */
    public suspend fun editMessage(
        channelId: ChannelId,
        messageId: MessageId,
        newContent: String,
    ): Result<ChatMessage>

    /** Delete a message. */
    public suspend fun deleteMessage(channelId: ChannelId, messageId: MessageId): Result<Unit>

    /** Load up to [limit] messages strictly older than [beforeId]. */
    public suspend fun loadOlder(
        channelId: ChannelId,
        beforeId: MessageId,
        limit: Int,
    ): Result<List<ChatMessage>>

    /** Load the most recent [limit] messages for [channelId] (no `before` cursor). */
    public suspend fun loadInitial(
        channelId: ChannelId,
        limit: Int,
    ): Result<List<ChatMessage>>
}
