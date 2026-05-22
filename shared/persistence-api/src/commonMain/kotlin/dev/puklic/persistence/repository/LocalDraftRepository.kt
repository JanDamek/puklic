package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import kotlinx.datetime.Instant

data class LocalDraft(
    val channelId: ChannelId,
    val content: String,
    val updatedAt: Instant,
)

interface LocalDraftRepository {
    suspend fun findByChannel(channelId: ChannelId): LocalDraft?
    suspend fun persist(draft: LocalDraft)
    suspend fun delete(channelId: ChannelId)
}
