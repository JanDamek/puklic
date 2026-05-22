package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import kotlinx.datetime.Instant

data class ReadState(
    val channelId: ChannelId,
    val lastMessageId: MessageId?,
    val mentionCount: Int,
    val lastViewedAt: Instant?,
)

interface ReadStateRepository {
    suspend fun findByChannel(channelId: ChannelId): ReadState?
    suspend fun findAll(): List<ReadState>
    suspend fun persist(state: ReadState)
    suspend fun delete(channelId: ChannelId)
}
