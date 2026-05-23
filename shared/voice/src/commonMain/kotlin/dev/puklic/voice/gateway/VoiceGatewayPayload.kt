package dev.puklic.voice.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Voice gateway envelope, mirroring `{ "op": Int, "d": JsonElement }` used on the main gateway.
 * See `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5.
 */
@Serializable
internal data class VoiceFrame(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

@Serializable
internal data class VoiceHello(
    @SerialName("heartbeat_interval") val heartbeatInterval: Double,
)

@Serializable
internal data class VoiceIdentify(
    @SerialName("server_id") val serverId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_id") val sessionId: String,
    val token: String,
)

@Serializable
internal data class VoiceReady(
    val ssrc: Int,
    val ip: String,
    val port: Int,
    val modes: List<String>,
)

@Serializable
internal data class VoiceHeartbeat(
    val t: Long,
)

@Serializable
internal data class VoiceHeartbeatAck(
    val t: Long,
)

@Serializable
internal data class VoiceSelectProtocolData(
    val address: String,
    val port: Int,
    val mode: String,
)

@Serializable
internal data class VoiceSelectProtocol(
    val protocol: String,
    val data: VoiceSelectProtocolData,
)

@Serializable
internal data class VoiceSessionDescription(
    val mode: String,
    @SerialName("secret_key") val secretKey: List<Int>,
)

@Serializable
internal data class VoiceSpeaking(
    val speaking: Int,
    val delay: Int = 0,
    val ssrc: Int,
    @SerialName("user_id") val userId: String? = null,
)

@Serializable
internal data class VoiceResume(
    @SerialName("server_id") val serverId: String,
    @SerialName("session_id") val sessionId: String,
    val token: String,
)

@Serializable
internal data class VoiceResumed(val ok: Boolean = true)

@Serializable
internal data class VoiceClientDisconnect(
    @SerialName("user_id") val userId: String,
)
