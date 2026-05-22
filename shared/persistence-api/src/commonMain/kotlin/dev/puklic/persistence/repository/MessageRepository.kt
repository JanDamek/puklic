package dev.puklic.persistence.repository

import dev.puklic.domain.ChatMessage
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Repository for persisted [ChatMessage]s. Provides reactive observation of a channel's message
 * stream plus paged scroll-back loading. The repository is the sole owner of DB writes for messages.
 */
interface MessageRepository {
    /**
     * Hot Flow of the most recent messages for [channelId], ordered by ascending timestamp.
     * Emits a new list on every persistence-layer change.
     */
    fun observe(channelId: ChannelId, limit: Int = DEFAULT_LIMIT): Flow<List<ChatMessage>>

    /**
     * Load messages older than [before] for the given channel (scroll-back pagination).
     * Returns at most [limit] messages, ordered by ascending timestamp.
     */
    suspend fun loadOlder(channelId: ChannelId, before: Instant, limit: Int = DEFAULT_LIMIT): List<ChatMessage>

    /** Persist or update a single message (INSERT OR REPLACE). */
    suspend fun persist(message: ChatMessage)

    /** Persist a batch of messages in a single transaction. */
    suspend fun persistAll(messages: List<ChatMessage>)

    /** Look up a single message by id, or null if absent from the cache. */
    suspend fun findById(id: MessageId): ChatMessage?

    /** Delete a single message by id. */
    suspend fun delete(id: MessageId)

    /** Clear all messages for a channel (e.g. on cache wipe or channel removal). */
    suspend fun clear(channelId: ChannelId)

    companion object {
        const val DEFAULT_LIMIT: Int = 200
    }
}
