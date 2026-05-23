package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId

/**
 * Ephemeral, in-memory representation of a single user's current voice-channel presence.
 *
 * Built from `VOICE_STATE_UPDATE` dispatches plus the initial `voice_states` array delivered
 * inside each `GUILD_CREATE`. Never persisted (voice presence is purely runtime state — see
 * `docs/03_infrastructure/architect-reports/2026-05-23-voice.md`).
 */
public data class VoiceMember(
    val userId: UserId,
    val channelId: ChannelId,
    val guildId: GuildId,
    val selfMute: Boolean,
    val selfDeaf: Boolean,
    val muted: Boolean,
    val deafened: Boolean,
)
