package dev.puklic.protocol.discord.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Generic Gateway frame envelope. Discord wraps every opcode in `{op, d, s?, t?}`.
 *
 * `d` is kept as raw `JsonElement` so the connection can dispatch by `(op, t)` and let
 * the caller deserialize the payload-specific DTO.
 */
@Serializable
internal data class GatewayFrame(
    val op: Int,
    val d: JsonElement? = null,
    val s: Int? = null,
    val t: String? = null,
)

internal object Opcode {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val VOICE_STATE_UPDATE = 4
    const val RESUME = 6
    const val RECONNECT = 7
    const val REQUEST_GUILD_MEMBERS = 8
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
}

@Serializable
internal data class GatewayHello(
    @SerialName("heartbeat_interval") val heartbeatInterval: Long,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class GatewayIdentify(
    val token: String,
    val properties: kotlinx.serialization.json.JsonElement,
    val capabilities: Int,
    val compress: Boolean = false,
    @SerialName("large_threshold") val largeThreshold: Int = 50,
    @EncodeDefault val presence: IdentifyPresence = IdentifyPresence(),
    @EncodeDefault @SerialName("client_state") val clientState: IdentifyClientState = IdentifyClientState(),
)

/**
 * Initial presence sent inside IDENTIFY. Matches Acheron's UpdatePresence shape.
 * `status = "unknown"` so Discord preserves the user's previous status across reconnects.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class IdentifyPresence(
    @EncodeDefault val status: String = "unknown",
    @EncodeDefault val since: Long = 0,
    @EncodeDefault val activities: List<String> = emptyList(),
    @EncodeDefault val afk: Boolean = false,
)

/**
 * Client state snapshot — minimum fields required by Discord's user-mode gateway. Sending
 * defaults of -1 / "0" / 0 tells Discord we have no cached state and to ship full payloads.
 */
@Serializable
internal data class IdentifyClientState(
    @SerialName("guild_versions") val guildVersions: Map<String, Int> = emptyMap(),
    @SerialName("highest_last_message_id") val highestLastMessageId: String = "0",
    @SerialName("read_state_version") val readStateVersion: Int = 0,
    @SerialName("user_guild_settings_version") val userGuildSettingsVersion: Int = -1,
    @SerialName("user_settings_version") val userSettingsVersion: Int = -1,
    @SerialName("private_channels_version") val privateChannelsVersion: String = "0",
    @SerialName("api_code_version") val apiCodeVersion: Int = 0,
)

@Serializable
internal data class IdentifyProperties(
    val os: String,
    val browser: String,
    val device: String,
)

@Serializable
internal data class GatewayResume(
    val token: String,
    @SerialName("session_id") val sessionId: String,
    val seq: Int,
)

/** Sent payload for op 1 — current `s` or null when none received yet. */
@Serializable
internal data class GatewayHeartbeat(val d: Int? = null)

@Serializable
internal data class ReadyEvent(
    @SerialName("session_id") val sessionId: String,
    @SerialName("resume_gateway_url") val resumeGatewayUrl: String,
    val user: DiscordUserDto,
    val guilds: List<DiscordGuildDto> = emptyList(),
    @SerialName("private_channels") val privateChannels: List<DiscordChannelDto> = emptyList(),
    val v: Int = 0,
)
