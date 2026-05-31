package dev.puklic.voice.gateway

import dev.puklic.voice.codec.PuklicVoiceCodec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Voice gateway envelope, mirroring `{ "op": Int, "d": JsonElement }` used on the main gateway.
 * See `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5.
 */
@Serializable
@PuklicVoiceCodec
public data class VoiceFrame(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceHello(
    @SerialName("heartbeat_interval") val heartbeatInterval: Double,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceIdentify(
    @SerialName("server_id") val serverId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String,
    val token: String,
    @SerialName("max_dave_protocol_version") val maxDaveProtocolVersion: Int = 1,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceReady(
    val ssrc: Int,
    @SerialName("video_ssrc") val videoSsrc: Int = 0,
    val ip: String,
    val port: Int,
    val modes: List<String>,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceHeartbeat(
    val t: Long,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceHeartbeatAck(
    val t: Long,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceCodecEntry(
    val name: String,
    val type: String,
    @SerialName("payload_type") val payloadType: Int,
    @SerialName("rtx_payload_type") val rtxPayloadType: Int? = null,
    val priority: Int,
    val encode: Boolean,
    val decode: Boolean,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceSelectProtocolData(
    val address: String,
    val port: Int,
    val mode: String,
    val codecs: List<VoiceCodecEntry> = emptyList(),
)

@Serializable
@PuklicVoiceCodec
public data class VoiceSelectProtocol(
    val protocol: String,
    val data: VoiceSelectProtocolData,
)

/**
 * Op 12 VideoStream (client → server). Notifies Discord which SSRCs carry the screenshare
 * video + RTX, plus simulcast stream descriptors. See architect report §4.
 */
@Serializable
@PuklicVoiceCodec
public data class Op12VideoStream(
    @SerialName("audio_ssrc") val audioSsrc: Int,
    @SerialName("video_ssrc") val videoSsrc: Int,
    @SerialName("rtx_ssrc") val rtxSsrc: Int = 0,
    val streams: List<StreamSpec>,
) {
    @Serializable
    public data class StreamSpec(
        val type: String = "video",
        val rid: String = "100",
        val quality: Int = 100,
        val ssrc: Int,
        @SerialName("rtx_ssrc") val rtxSsrc: Int = 0,
        @SerialName("max_bitrate") val maxBitrate: Int = 2_500_000,
        val active: Boolean,
    )
}

@Serializable
@PuklicVoiceCodec
public data class VoiceSessionDescription(
    val mode: String,
    @SerialName("secret_key") val secretKey: List<Int>,
    @SerialName("dave_protocol_version") val daveProtocolVersion: Int? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceSpeaking(
    val speaking: Int,
    val delay: Int = 0,
    val ssrc: Int,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceResume(
    @SerialName("server_id") val serverId: String,
    @SerialName("session_id") val sessionId: String,
    val token: String,
)

@Serializable
@PuklicVoiceCodec
public data class VoiceResumed(val ok: Boolean = true)

@Serializable
@PuklicVoiceCodec
public data class VoiceClientDisconnect(
    @SerialName("user_id") val userId: String,
)
