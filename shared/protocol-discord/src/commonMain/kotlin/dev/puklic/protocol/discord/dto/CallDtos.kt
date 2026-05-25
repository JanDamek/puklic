package dev.puklic.protocol.discord.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Inbound dispatch payload for `CALL_CREATE` (DM voice ring).
 *
 * Discord delivers this on the main gateway when someone initiates a 1:1 or group-DM voice
 * call that includes the current user. `ringing` is the array of user ids whose clients are
 * being rung (typically all recipients except the caller); `voice_states` may already contain
 * the caller's own [VoiceStateUpdateDto] entry, allowing synchronous caller-id resolution
 * (chain step 1 per architect-report 2026-05-25-dm-incoming-voice §4).
 */
@Serializable
internal data class CallCreateDto(
    @SerialName("channel_id") val channelId: String,
    @SerialName("message_id") val messageId: String? = null,
    val region: String? = null,
    val ringing: List<String> = emptyList(),
    @SerialName("voice_states") val voiceStates: List<VoiceStateUpdateDto> = emptyList(),
    val unavailable: Boolean = false,
)

/**
 * Inbound dispatch payload for `CALL_UPDATE` — fires when the `ringing` set changes (e.g. a
 * recipient picks up or declines elsewhere). `voice_states` is intentionally not modeled here:
 * voice state churn flows through `VOICE_STATE_UPDATE` dispatches.
 */
@Serializable
internal data class CallUpdateDto(
    @SerialName("channel_id") val channelId: String,
    @SerialName("message_id") val messageId: String? = null,
    val region: String? = null,
    val ringing: List<String> = emptyList(),
)

/**
 * Inbound dispatch payload for `CALL_DELETE`. `unavailable=true` means voice region outage
 * (call temporarily unavailable); `unavailable=false` (or absent) means the call has ended.
 * V1 treats both identically — pop the queue entry.
 */
@Serializable
internal data class CallDeleteDto(
    @SerialName("channel_id") val channelId: String,
    val unavailable: Boolean = false,
)

/** Body for `POST /channels/{channel_id}/call/stop-ringing`. */
@Serializable
internal data class StopRingingDto(val recipients: List<String>?)
