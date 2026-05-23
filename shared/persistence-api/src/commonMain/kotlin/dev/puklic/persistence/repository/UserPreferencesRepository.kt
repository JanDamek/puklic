package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId

/**
 * Last-position persistence: which guild + channel the user was viewing when the app was last
 * closed, so the next startup can restore it instead of opening to an empty MainScreen.
 *
 * Storage is intentionally a flat key/value table (`user_preferences`). Not credentials —
 * file-based, plain. Schema is additive (`CREATE TABLE IF NOT EXISTS`) so existing DBs upgrade
 * without losing rows.
 */
public interface UserPreferencesRepository {
    public suspend fun lastPosition(): LastPosition

    /**
     * Persists the user's current navigation position. Pass `LastPosition.Empty` to clear.
     */
    public suspend fun setLastPosition(position: LastPosition)
}

public sealed interface LastPosition {
    public data object Empty : LastPosition
    public data class DmHome(val channelId: ChannelId?) : LastPosition
    public data class Guild(val guildId: GuildId, val channelId: ChannelId?) : LastPosition
}
