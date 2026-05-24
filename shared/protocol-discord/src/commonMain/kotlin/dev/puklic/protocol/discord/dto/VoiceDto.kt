package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inbound dispatch payload for `VOICE_STATE_UPDATE`. Mirrors the user-mode/bot gateway shape per
 * `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5. Only fields consumed by the
 * voice trigger flow are modeled; other Discord-side fields (`member`, `request_to_speak_timestamp`,
 * etc.) are tolerated and ignored by [dev.puklic.protocol.discord.DiscordJson].
 */
@Serializable
internal data class VoiceStateUpdateDto(
    @SerialName("guild_id") val guildId: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("self_mute") val selfMute: Boolean = false,
    @SerialName("self_deaf") val selfDeaf: Boolean = false,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val suppress: Boolean = false,
)

/**
 * Inbound dispatch payload for `VOICE_SERVER_UPDATE`. `endpoint` may be `null` during a region
 * migration — the client must wait for a follow-up event with a non-null endpoint before opening
 * the voice WS (architect report §5).
 */
@Serializable
internal data class VoiceServerUpdateDto(
    val token: String,
    @SerialName("guild_id") val guildId: String? = null,
    val endpoint: String? = null,
)
