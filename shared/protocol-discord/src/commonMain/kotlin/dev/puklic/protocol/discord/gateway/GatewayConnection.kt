package dev.puklic.protocol.discord.gateway

import dev.puklic.protocol.discord.Capabilities
import dev.puklic.protocol.discord.DiscordClientProperties
import dev.puklic.protocol.discord.DiscordJson
import dev.puklic.protocol.discord.SuperPropertiesJson
import dev.puklic.protocol.discord.buildClientProperties
import dev.puklic.protocol.discord.dto.GatewayFrame
import dev.puklic.protocol.discord.dto.GatewayHello
import dev.puklic.protocol.discord.dto.GatewayIdentify
import dev.puklic.protocol.discord.dto.GatewayResume
import dev.puklic.protocol.discord.dto.Opcode
import dev.puklic.protocol.discord.dto.ReadyEvent
import co.touchlab.kermit.Logger
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val GATEWAY_URL_DEFAULT = "wss://gateway.discord.gg/?v=10&encoding=json"

/** Discord-defined close codes for the gateway, per `docs/02_domain/discord-protocol.md`. */
internal object CloseCode {
    const val UNKNOWN_ERROR = 4000
    const val UNKNOWN_OPCODE = 4001
    const val DECODE_ERROR = 4002
    const val NOT_AUTHENTICATED = 4003
    const val AUTH_FAILED = 4004
    const val ALREADY_AUTHENTICATED = 4005
    const val INVALID_SEQ = 4007
    const val RATE_LIMITED = 4008
    const val SESSION_TIMEOUT = 4009
    const val INVALID_SHARD = 4010
    const val SHARDING_REQUIRED = 4011
    const val INVALID_API_VERSION = 4012
    const val INVALID_INTENTS = 4013
    const val DISALLOWED_INTENTS = 4014
}

/**
 * Discord gateway client — state machine driven by an injected [GatewayTransportFactory] so the
 * full lifecycle (HELLO → IDENTIFY → READY, heartbeat, close-code dispatch) is testable without
 * any live network. Tokens are never logged.
 */
public class GatewayConnection(
    private val scope: CoroutineScope,
    private val token: String,
    private val transportFactory: GatewayTransportFactory,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    clientProperties: DiscordClientProperties = buildClientProperties(),
) {
    // Emit ALL identity fields (defaults included) — Discord's IDENTIFY parser expects the
    // full client-properties payload, identical to the X-Super-Properties JSON.
    private val identifyProperties: JsonElement =
        SuperPropertiesJson.encodeToJsonElement(DiscordClientProperties.serializer(), clientProperties)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    public val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayDispatchEvent>(extraBufferCapacity = EVENT_BUFFER)
    public val events: SharedFlow<GatewayDispatchEvent> = _events.asSharedFlow()

    private var workerJob: Job? = null
    private var sequence: Int = 0
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var activeTransport: GatewayTransport? = null

    public fun connect(url: String = GATEWAY_URL_DEFAULT) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runLoop(url) }
    }

    public suspend fun disconnect() {
        workerJob?.cancelAndJoin()
        workerJob = null
        _state.value = GatewayState.Disconnected
    }

    private suspend fun runLoop(url: String) {
        _state.value = GatewayState.Connecting(attemptCount = 1)
        val transport = transportFactory(url)
        activeTransport = transport
        try {
            transport.incoming.collect { frame ->
                when (frame) {
                    is GatewayFrameIn.Text -> handleText(transport, frame.text)
                    is GatewayFrameIn.Binary -> {
                        // Reserved for zlib-stream path; gateway text path only for now.
                    }
                    is GatewayFrameIn.Close -> {
                        handleClose(frame.code)
                        return@collect
                    }
                }
            }
        } finally {
            // Coroutine ends; state stays whatever transition handleClose set
            activeTransport = null
        }
    }

    /**
     * Send an OP 37 `GUILD_SUBSCRIPTIONS_BULK` frame — modern replacement for the now-legacy
     * OP 14 `lazy_guild_subscribe`. Discord's current user-mode client (and Acheron) use this
     * to subscribe to one or more guilds in a single frame.
     *
     * JSON shape:
     *   {"op": 37, "d": {"subscriptions": {"<gid>": {"typing": true, "activities": true,
     *                    "threads": true, "channels": {"<chId>": [[0,99]]}}}}}
     *
     * No-ops if the gateway is not currently READY.
     */
    public suspend fun guildSubscriptionsBulk(subscriptions: Map<GuildId, List<ChannelId>>) {
        val transport = activeTransport
        if (transport == null) {
            Logger.w("GatewayConnection") {
                "OP37 skipped: no active transport (guilds=${subscriptions.keys.map { it.value }})"
            }
            return
        }
        if (subscriptions.isEmpty()) return
        Logger.i("GatewayConnection") {
            "OP37 sending guilds=${subscriptions.keys.map { it.value }} " +
                "channelCounts=${subscriptions.mapValues { it.value.size }.values}"
        }
        val payload = buildJsonObject {
            put("op", JsonPrimitive(GUILD_SUBSCRIPTIONS_BULK_OPCODE))
            putJsonObject("d") {
                putJsonObject("subscriptions") {
                    subscriptions.forEach { (gid, channelIds) ->
                        putJsonObject(gid.value.toString()) {
                            put("typing", JsonPrimitive(true))
                            put("activities", JsonPrimitive(true))
                            put("threads", JsonPrimitive(true))
                            putJsonObject("channels") {
                                channelIds.forEach { channelId ->
                                    putJsonArray(channelId.value.toString()) {
                                        addJsonArray {
                                            add(JsonPrimitive(LAZY_RANGE_START))
                                            add(JsonPrimitive(LAZY_RANGE_END))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        transport.sendText(DiscordJson.encodeToString(JsonElement.serializer(), payload))
        Logger.i("GatewayConnection") { "OP37 sent" }
    }

    /**
     * Back-compat shim — delegates to [guildSubscriptionsBulk] with a single-guild entry. Existing
     * call sites (e.g. `MainViewModel.selectGuild` / `selectChannel`) keep working without change.
     */
    public suspend fun lazyRequestGuild(guildId: GuildId, channelIds: List<ChannelId>) {
        guildSubscriptionsBulk(mapOf(guildId to channelIds))
    }

    private suspend fun handleText(transport: GatewayTransport, text: String) {
        val envelope = DiscordJson.decodeFromString(GatewayFrame.serializer(), text)
        envelope.s?.let { sequence = it }
        when (envelope.op) {
            Opcode.HELLO -> {
                val hello = DiscordJson.decodeFromJsonElement(GatewayHello.serializer(), requireD(envelope))
                _state.value = GatewayState.HelloReceived(hello.heartbeatInterval)
                sendIdentify(transport)
                _state.value = GatewayState.Identifying(hello.heartbeatInterval)
            }
            Opcode.HEARTBEAT_ACK -> {
                // ACK received — connection is healthy.
            }
            Opcode.HEARTBEAT -> {
                // Server-requested heartbeat — reply immediately with current sequence.
                sendHeartbeat(transport)
            }
            Opcode.INVALID_SESSION -> {
                // Session invalidated; non-resumable.
                sessionId = null
                sequence = 0
            }
            Opcode.DISPATCH -> handleDispatch(envelope)
            Opcode.RECONNECT -> {
                // Server requesting reconnect — close and let outer logic reconnect via resume.
                transport.close(code = GatewayTransport.NORMAL_CLOSURE, reason = "server-requested reconnect")
            }
        }
    }

    private fun handleDispatch(envelope: GatewayFrame) {
        val type = envelope.t ?: return
        val payload = envelope.d ?: return
        if (type == "READY") {
            val ready = DiscordJson.decodeFromJsonElement(ReadyEvent.serializer(), payload)
            sessionId = ready.sessionId
            resumeGatewayUrl = ready.resumeGatewayUrl
            _state.value = GatewayState.Ready(
                sessionId = ready.sessionId,
                resumeGatewayUrl = ready.resumeGatewayUrl,
                sequence = envelope.s ?: 0,
            )
        }
        _events.tryEmit(GatewayDispatchEvent(type = type, sequence = envelope.s ?: 0, payload = payload))
    }

    private fun handleClose(code: Int) {
        _state.value = when (code) {
            CloseCode.AUTH_FAILED -> GatewayState.TokenInvalid
            CloseCode.INVALID_SEQ,
            CloseCode.SESSION_TIMEOUT,
            CloseCode.RATE_LIMITED,
            CloseCode.UNKNOWN_ERROR,
            -> sessionId?.let { GatewayState.Resuming(sessionId = it, sequence = sequence) }
                ?: GatewayState.Disconnected
            else -> GatewayState.Disconnected
        }
    }

    private suspend fun sendIdentify(transport: GatewayTransport) {
        val existingSession = sessionId
        val payload: JsonElement = if (existingSession != null && sequence > 0) {
            buildJsonObject {
                put("op", JsonPrimitive(Opcode.RESUME))
                put(
                    "d",
                    DiscordJson.encodeToJsonElement(
                        GatewayResume.serializer(),
                        GatewayResume(token = token, sessionId = existingSession, seq = sequence),
                    ),
                )
            }
        } else {
            buildJsonObject {
                put("op", JsonPrimitive(Opcode.IDENTIFY))
                put(
                    "d",
                    DiscordJson.encodeToJsonElement(
                        GatewayIdentify.serializer(),
                        GatewayIdentify(
                            token = token,
                            properties = identifyProperties,
                            capabilities = Capabilities.CAPABILITIES_VERSION,
                        ),
                    ),
                )
            }
        }
        transport.sendText(DiscordJson.encodeToString(JsonElement.serializer(), payload))
    }

    private suspend fun sendHeartbeat(transport: GatewayTransport) {
        val payload = buildJsonObject {
            put("op", JsonPrimitive(Opcode.HEARTBEAT))
            put("d", if (sequence == 0) JsonPrimitive(null as String?) else JsonPrimitive(sequence))
        }
        transport.sendText(DiscordJson.encodeToString(JsonElement.serializer(), payload))
        // Touch `now` to keep the clock injected for future jitter logic.
        now()
    }

    private fun requireD(envelope: GatewayFrame): JsonElement =
        envelope.d ?: error("Gateway frame op=${envelope.op} missing required `d` field")

    @Suppress("UnusedPrivateMember")
    private fun firstSequenceFromPrimitive(primitive: JsonPrimitive?): Int? =
        primitive?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

    private companion object {
        const val EVENT_BUFFER = 64
        const val GUILD_SUBSCRIPTIONS_BULK_OPCODE = 37
        const val LAZY_RANGE_START = 0
        const val LAZY_RANGE_END = 99
    }
}

