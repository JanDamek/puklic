package dev.puklic.persistence.repository

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId

/**
 * Wire codec for [LastPosition], used to persist the user's navigation position
 * in cross-device synced storage (iCloud Keychain via SecureStorage).
 *
 * Wire format:
 *   Empty                              -> ""
 *   DmHome(null)                       -> "dm"
 *   DmHome(ChannelId(5))               -> "dm:5"
 *   Guild(GuildId(10), null)           -> "guild:10"
 *   Guild(GuildId(10), ChannelId(20))  -> "guild:10:20"
 *
 * [decode] is the inverse and is lenient: null, empty, or any malformed input
 * maps to [LastPosition.Empty].
 */
public object LastPositionCodec {

    public fun encode(position: LastPosition): String = when (position) {
        LastPosition.Empty -> ""
        is LastPosition.DmHome ->
            if (position.channelId == null) "dm" else "dm:${position.channelId.value}"
        is LastPosition.Guild -> buildString {
            append("guild:")
            append(position.guildId.value)
            position.channelId?.let {
                append(":")
                append(it.value)
            }
        }
    }

    public fun decode(raw: String?): LastPosition {
        if (raw.isNullOrEmpty()) return LastPosition.Empty
        val parts = raw.split(":")
        return when (parts[0]) {
            "dm" -> decodeDm(parts)
            "guild" -> decodeGuild(parts)
            else -> LastPosition.Empty
        }
    }

    private fun decodeDm(parts: List<String>): LastPosition = when (parts.size) {
        1 -> LastPosition.DmHome(null)
        2 -> parts[1].toLongOrNull()
            ?.let { LastPosition.DmHome(ChannelId(it)) }
            ?: LastPosition.Empty
        else -> LastPosition.Empty
    }

    private fun decodeGuild(parts: List<String>): LastPosition {
        val guildId = parts.getOrNull(1)?.toLongOrNull() ?: return LastPosition.Empty
        return when (parts.size) {
            2 -> LastPosition.Guild(GuildId(guildId), null)
            3 -> parts[2].toLongOrNull()
                ?.let { LastPosition.Guild(GuildId(guildId), ChannelId(it)) }
                ?: LastPosition.Empty
            else -> LastPosition.Empty
        }
    }
}
