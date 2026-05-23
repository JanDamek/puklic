package dev.puklic.persistence.sqldelight

import dev.puklic.db.PuklicDatabase
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.persistence.repository.LastPosition
import dev.puklic.persistence.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * SQLDelight-backed implementation of [UserPreferencesRepository]. Stores the last-position
 * tuple as three string keys so the table schema stays generic for future preferences.
 */
public class UserPreferencesRepositoryImpl(
    private val db: PuklicDatabase,
    private val dispatcher: CoroutineDispatcher,
) : UserPreferencesRepository {

    override suspend fun lastPosition(): LastPosition = withContext(dispatcher) {
        val scope = read(KEY_SCOPE)
        val guildId = read(KEY_GUILD)?.toLongOrNull()?.let(::GuildId)
        val channelId = read(KEY_CHANNEL)?.toLongOrNull()?.let(::ChannelId)
        when (scope) {
            SCOPE_DM_HOME -> LastPosition.DmHome(channelId)
            SCOPE_GUILD -> guildId?.let { LastPosition.Guild(it, channelId) } ?: LastPosition.Empty
            else -> LastPosition.Empty
        }
    }

    override suspend fun setLastPosition(position: LastPosition) {
        withContext(dispatcher) {
            db.transaction {
                when (position) {
                    is LastPosition.Empty -> {
                        db.userPreferencesQueries.delete(KEY_SCOPE)
                        db.userPreferencesQueries.delete(KEY_GUILD)
                        db.userPreferencesQueries.delete(KEY_CHANNEL)
                    }
                    is LastPosition.DmHome -> {
                        write(KEY_SCOPE, SCOPE_DM_HOME)
                        db.userPreferencesQueries.delete(KEY_GUILD)
                        writeOrDelete(KEY_CHANNEL, position.channelId?.value?.toString())
                    }
                    is LastPosition.Guild -> {
                        write(KEY_SCOPE, SCOPE_GUILD)
                        write(KEY_GUILD, position.guildId.value.toString())
                        writeOrDelete(KEY_CHANNEL, position.channelId?.value?.toString())
                    }
                }
            }
        }
    }

    private fun read(key: String): String? =
        db.userPreferencesQueries.selectByKey(key).executeAsOneOrNull()

    private fun write(key: String, value: String) {
        db.userPreferencesQueries.upsert(key, value)
    }

    private fun writeOrDelete(key: String, value: String?) {
        if (value == null) db.userPreferencesQueries.delete(key) else write(key, value)
    }

    private companion object {
        const val KEY_SCOPE = "nav.scope"
        const val KEY_GUILD = "nav.guildId"
        const val KEY_CHANNEL = "nav.channelId"
        const val SCOPE_DM_HOME = "dmHome"
        const val SCOPE_GUILD = "guild"
    }
}
