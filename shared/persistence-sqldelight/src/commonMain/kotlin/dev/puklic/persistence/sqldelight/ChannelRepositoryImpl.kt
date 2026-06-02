package dev.puklic.persistence.sqldelight

import app.cash.sqldelight.coroutines.asFlow
import dev.puklic.db.PuklicDatabase
import dev.puklic.domain.Channel
import dev.puklic.domain.ChannelType
import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.domain.OverwriteType
import dev.puklic.domain.PermissionOverwrite
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ChannelRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dev.puklic.db.Channel as ChannelRow

class ChannelRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
    private val nowEpochMs: () -> Long,
) : ChannelRepository {

    override fun observeByGuild(guildId: GuildId): Flow<List<Channel>> =
        db.channelQueries.selectByGuild(guildId.value).asFlow()
            .map { withContext(dispatcher) { it.executeAsList().mapNotNull { row -> row.toDomain() } } }
            .flowOn(dispatcher)

    override suspend fun findById(id: ChannelId): Channel? = withContext(dispatcher) {
        db.channelQueries.selectById(id.value).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun persist(channel: Channel) {
        withContext(dispatcher) { insert(channel) }
    }

    override suspend fun persistAll(channels: List<Channel>) {
        if (channels.isEmpty()) return
        withContext(dispatcher) { db.transaction { channels.forEach { insert(it) } } }
    }

    override suspend fun updateLastMessage(id: ChannelId, lastMessageId: MessageId) {
        withContext(dispatcher) {
            db.channelQueries.updateLastMessage(
                last_message_id = lastMessageId.value,
                updated_at = nowEpochMs(),
                id = id.value,
            )
        }
    }

    override suspend fun delete(id: ChannelId) {
        withContext(dispatcher) { db.channelQueries.delete(id.value) }
    }

    private fun insert(channel: Channel) {
        when (channel) {
            is GuildTextChannel -> db.channelQueries.upsert(
                id = channel.id.value,
                guild_id = channel.guildId.value,
                parent_id = channel.parentId?.value,
                type = channel.type.ordinal.toLong(),
                name = channel.name,
                topic = channel.topic,
                position = channel.position.toLong(),
                rate_limit_per_user = channel.rateLimitPerUser.toLong(),
                nsfw = if (channel.nsfw) 1L else 0L,
                bitrate = null,
                user_limit = null,
                last_message_id = null,
                updated_at = nowEpochMs(),
                permission_overwrites_json = (channel as? GuildChannel)?.let { encodeOverwrites(it.permissionOverwrites) },
            )
            is GuildVoiceChannel -> db.channelQueries.upsert(
                id = channel.id.value,
                guild_id = channel.guildId.value,
                parent_id = channel.parentId?.value,
                type = channel.type.ordinal.toLong(),
                name = channel.name,
                topic = null,
                position = channel.position.toLong(),
                rate_limit_per_user = 0L,
                nsfw = 0L,
                bitrate = channel.bitrate?.toLong(),
                user_limit = channel.userLimit?.toLong(),
                last_message_id = null,
                updated_at = nowEpochMs(),
                permission_overwrites_json = (channel as? GuildChannel)?.let { encodeOverwrites(it.permissionOverwrites) },
            )
            is GuildCategoryChannel -> db.channelQueries.upsert(
                id = channel.id.value,
                guild_id = channel.guildId.value,
                parent_id = null,
                type = channel.type.ordinal.toLong(),
                name = channel.name,
                topic = null,
                position = channel.position.toLong(),
                rate_limit_per_user = 0L,
                nsfw = 0L,
                bitrate = null,
                user_limit = null,
                last_message_id = null,
                updated_at = nowEpochMs(),
                permission_overwrites_json = (channel as? GuildChannel)?.let { encodeOverwrites(it.permissionOverwrites) },
            )
            is DmChannel -> db.channelQueries.upsert(
                id = channel.id.value,
                guild_id = null,
                parent_id = null,
                type = channel.type.ordinal.toLong(),
                name = null,
                topic = null,
                position = null,
                rate_limit_per_user = 0L,
                nsfw = 0L,
                bitrate = null,
                user_limit = null,
                last_message_id = null,
                updated_at = nowEpochMs(),
                permission_overwrites_json = (channel as? GuildChannel)?.let { encodeOverwrites(it.permissionOverwrites) },
            )
        }
    }

    /**
     * Reconstruct the domain [Channel] from a persisted row. Returns null for DM channels — the
     * recipients list lives outside the `channel` table; downstream callers must hydrate from
     * the `user` cache. Phase 1 returns null for DMs (callers don't request them yet).
     */
    private fun ChannelRow.toDomain(): Channel? {
        val ct = ChannelType.entries.getOrNull(type.toInt()) ?: return null
        val overwrites = decodeOverwrites(permission_overwrites_json)
        return when (ct) {
            ChannelType.GUILD_CATEGORY -> GuildCategoryChannel(
                id = ChannelId(id),
                name = name,
                guildId = GuildId(guild_id ?: return null),
                position = (position ?: 0L).toInt(),
                permissionOverwrites = overwrites,
            )
            ChannelType.GUILD_VOICE, ChannelType.GUILD_STAGE_VOICE -> GuildVoiceChannel(
                id = ChannelId(id),
                name = name,
                guildId = GuildId(guild_id ?: return null),
                position = (position ?: 0L).toInt(),
                parentId = parent_id?.let(::ChannelId),
                bitrate = bitrate?.toInt(),
                userLimit = user_limit?.toInt(),
                permissionOverwrites = overwrites,
            )
            ChannelType.GUILD_TEXT, ChannelType.GUILD_ANNOUNCEMENT -> GuildTextChannel(
                id = ChannelId(id),
                name = name,
                guildId = GuildId(guild_id ?: return null),
                parentId = parent_id?.let(::ChannelId),
                topic = topic,
                position = (position ?: 0L).toInt(),
                rateLimitPerUser = (rate_limit_per_user ?: 0L).toInt(),
                nsfw = (nsfw ?: 0L) != 0L,
                permissionOverwrites = overwrites,
            )
            ChannelType.DM, ChannelType.GROUP_DM -> null
            else -> null
        }
    }

    /**
     * Persist permission overwrites as compact JSON (issue #18 follow-up): the SQLite row
     * previously discarded them, so the cached channel list filter defaulted to @everyone-allow
     * and channels the user couldn't view still appeared in the rail. Format:
     * `[[targetId, type, allow, deny], ...]` — minimal token count, stable across schema.
     */
    private fun encodeOverwrites(list: List<PermissionOverwrite>): String? {
        if (list.isEmpty()) return null
        return list.joinToString(separator = ";", prefix = "", postfix = "") { ow ->
            "${ow.targetId},${ow.type.ordinal},${ow.allow},${ow.deny}"
        }
    }

    private fun decodeOverwrites(encoded: String?): List<PermissionOverwrite> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            if (parts.size != 4) return@mapNotNull null
            val targetId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val typeOrdinal = parts[1].toIntOrNull() ?: return@mapNotNull null
            val type = OverwriteType.entries.getOrNull(typeOrdinal) ?: return@mapNotNull null
            val allow = parts[2].toLongOrNull() ?: return@mapNotNull null
            val deny = parts[3].toLongOrNull() ?: return@mapNotNull null
            PermissionOverwrite(targetId = targetId, type = type, allow = allow, deny = deny)
        }
    }
}
