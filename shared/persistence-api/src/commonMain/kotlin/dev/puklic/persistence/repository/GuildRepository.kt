package dev.puklic.persistence.repository

import dev.puklic.domain.Guild
import dev.puklic.ids.GuildId
import kotlinx.coroutines.flow.Flow

interface GuildRepository {
    fun observeAll(): Flow<List<Guild>>
    suspend fun findById(id: GuildId): Guild?
    suspend fun persist(guild: Guild)
    suspend fun persistAll(guilds: List<Guild>)
    suspend fun delete(id: GuildId)
}
