package dev.puklic.persistence.repository

import dev.puklic.domain.Channel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import kotlinx.coroutines.flow.Flow

interface ChannelRepository {
    fun observeByGuild(guildId: GuildId): Flow<List<Channel>>
    suspend fun findById(id: ChannelId): Channel?
    suspend fun persist(channel: Channel)
    suspend fun persistAll(channels: List<Channel>)
    suspend fun updateLastMessage(id: ChannelId, lastMessageId: MessageId)
    suspend fun delete(id: ChannelId)
}
