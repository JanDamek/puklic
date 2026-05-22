package dev.puklic.protocol.discord.gateway

import dev.puklic.protocol.discord.Capabilities
import dev.puklic.protocol.discord.DiscordJson
import dev.puklic.protocol.discord.dto.GatewayFrame
import dev.puklic.protocol.discord.dto.GatewayHello
import dev.puklic.protocol.discord.dto.GatewayIdentify
import dev.puklic.protocol.discord.dto.GatewayResume
import dev.puklic.protocol.discord.dto.IdentifyProperties
import dev.puklic.protocol.discord.dto.Opcode
import dev.puklic.protocol.discord.dto.ReadyEvent
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
) {
    private val _state = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    public val state: StateFlow<GatewayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GatewayDispatchEvent>(extraBufferCapacity = EVENT_BUFFER)
    public val events: SharedFlow<GatewayDispatchEvent> = _events.asSharedFlow()

    private var workerJob: Job? = null
    private var sequence: Int = 0
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null

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
        }
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
                            properties = IdentifyProperties(os = "linux", browser = "puklic", device = "puklic"),
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
    }
}

