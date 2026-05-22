package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import co.touchlab.kermit.Logger
import dev.puklic.db.PuklicDatabase
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildFeature
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.GuildRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import dev.puklic.db.Guild as GuildRow

class GuildRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowEpochMs: () -> Long,
) : GuildRepository {

    override fun observeAll(): Flow<List<Guild>> =
        db.guildQueries.selectAll().asFlow()
            .map { withContext(dispatcher) { it.executeAsList().map { row -> row.toDomain() } } }
            .onEach { Logger.i(STORAGE_TAG) { "storage: observeAll emit, size=${it.size}" } }
            .flowOn(dispatcher)

    override suspend fun findById(id: GuildId): Guild? = withContext(dispatcher) {
        db.guildQueries.selectById(id.value).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun persist(guild: Guild) {
        withContext(dispatcher) { insert(guild) }
    }

    override suspend fun persistAll(guilds: List<Guild>) {
        if (guilds.isEmpty()) return
        withContext(dispatcher) {
            db.transaction { guilds.forEach { insert(it) } }
        }
    }

    override suspend fun delete(id: GuildId) {
        withContext(dispatcher) { db.guildQueries.delete(id.value) }
    }

    private fun insert(guild: Guild) {
        Logger.i(STORAGE_TAG) { "storage: INSERT guild id=${guild.id.value} name=${guild.name}" }
        db.guildQueries.upsert(
            id = guild.id.value,
            name = guild.name,
            icon_hash = guild.iconHash,
            owner_id = guild.ownerId.value,
            features = featuresSerializer.let { Companion.json.encodeToString(it, guild.features.map { f -> f.name }) },
            member_count = guild.memberCount?.toLong(),
            updated_at = nowEpochMs(),
        )
    }

    private fun GuildRow.toDomain(): Guild = Guild(
        id = GuildId(id),
        name = name,
        iconHash = icon_hash,
        ownerId = UserId(owner_id),
        features = Companion.json.decodeFromString(featuresSerializer, features)
            .mapNotNull { name -> runCatching { GuildFeature.valueOf(name) }.getOrNull() }
            .toSet(),
        memberCount = member_count?.toInt(),
    )

    private companion object {
        const val STORAGE_TAG = "GuildRepositoryImpl"
        val featuresSerializer = ListSerializer(String.serializer())
        val json get() = PersistenceJson
    }
}
