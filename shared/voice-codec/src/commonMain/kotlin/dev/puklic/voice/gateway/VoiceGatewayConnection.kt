package dev.puklic.voice.gateway

import kotlin.concurrent.Volatile

import dev.puklic.voice.codec.PuklicVoiceCodec

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Voice gateway state machine.
 *
 * Source of truth: `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5.
 *
 * Transitions:
 *   Idle → ConnectingWs → AwaitingHello → Identifying → AwaitingReady → Active
 *                                                                   ↘ Reconnecting → ConnectingWs
 *                                                                   ↘ Failed
 *
 * UDP, IP discovery, encryption and SelectProtocol/SessionDescription consumption belong to
 * slices 4..6. This slice only opens the socket, exchanges Hello/Identify/Ready, runs the
 * heartbeat loop, and exposes events.
 */
@PuklicVoiceCodec
public sealed interface VoiceGatewayState {
    data object Idle : VoiceGatewayState
    data class ConnectingWs(val attempt: Int) : VoiceGatewayState
    data object AwaitingHello : VoiceGatewayState
    data object Identifying : VoiceGatewayState
    data object AwaitingReady : VoiceGatewayState
    data class Active(val ssrc: Int) : VoiceGatewayState
    data class Reconnecting(val attempt: Int, val reason: String) : VoiceGatewayState
    data class Failed(val reason: String) : VoiceGatewayState
}

@PuklicVoiceCodec
public sealed interface VoiceGatewayEvent {
    data class Ready(
        val ssrc: Int,
        val ip: String,
        val port: Int,
        val modes: List<String>,
        val videoSsrc: Int = 0,
    ) : VoiceGatewayEvent
    data class SessionDescription(
        val mode: String,
        val secretKey: ByteArray,
        val daveProtocolVersion: Int = 0,
        /**
         * Negotiated video codec name as reported by Discord (e.g. "H264", "VP8"). `null` when the
         * server omits the field — callers fall back to the highest-priority codec they advertised
         * (H.264, per `DEFAULT_CODECS`).
         */
        val videoCodec: String? = null,
    ) : VoiceGatewayEvent {
        override fun equals(other: Any?): Boolean =
            other is SessionDescription && mode == other.mode &&
                secretKey.contentEquals(other.secretKey) &&
                daveProtocolVersion == other.daveProtocolVersion &&
                videoCodec == other.videoCodec
        override fun hashCode(): Int {
            var h = mode.hashCode()
            h = 31 * h + secretKey.contentHashCode()
            h = 31 * h + daveProtocolVersion
            h = 31 * h + (videoCodec?.hashCode() ?: 0)
            return h
        }
    }
    data class Speaking(val userId: String, val ssrc: Int, val flags: Int) : VoiceGatewayEvent
    data class ClientDisconnect(val userId: String) : VoiceGatewayEvent
}

@PuklicVoiceCodec
public interface VoiceGatewayConnection {
    val state: StateFlow<VoiceGatewayState>
    val events: SharedFlow<VoiceGatewayEvent>
    suspend fun connect(endpoint: String, token: String, sessionId: String, serverId: String, userId: String)
    suspend fun sendSelectProtocol(externalIp: String, externalPort: Int, mode: String)
    suspend fun sendSpeaking(speaking: Int, ssrc: Int)
    suspend fun sendVideoStream(audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, active: Boolean)
    /** Send a raw binary frame (used for DAVE opcodes 25-30). */
    suspend fun sendBinary(bytes: ByteArray)
    /** Send a raw JSON envelope `{ "op": op, "d": <jsonBody> }` (used for DAVE 21-24, 31). */
    suspend fun sendDaveJson(op: Int, jsonBody: String)
    /** Install a handler for incoming DAVE binary frames (opcodes 25-30). */
    fun setDaveBinaryHandler(handler: suspend (op: Int, payload: ByteArray) -> Unit)
    /** Install a handler for incoming DAVE JSON ops (21-24, 31). */
    fun setDaveJsonHandler(handler: suspend (op: Int, body: kotlinx.serialization.json.JsonElement) -> Unit)
    suspend fun close()
}

/**
 * Default [VoiceGatewayConnection]. The transport is injected so tests can drive the protocol
 * with a fake socket — same pattern as `GatewayConnection` in `:shared:protocol-discord`.
 *
 * @param maxMissedAcks two missed heartbeat acks → reconnect, per architect report §5.
 * @param maxReconnectAttempts exponential backoff caps at 5 (1/2/4/8/16 s) per §5.
 */
@PuklicVoiceCodec
public class DefaultVoiceGatewayConnection(
    private val scope: CoroutineScope,
    private val transportFactory: VoiceGatewayTransportFactory,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { 0L },
    private val maxMissedAcks: Int = DEFAULT_MAX_MISSED_ACKS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
) : VoiceGatewayConnection {

    private val _state = MutableStateFlow<VoiceGatewayState>(VoiceGatewayState.Idle)
    override val state: StateFlow<VoiceGatewayState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceGatewayEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<VoiceGatewayEvent> = _events.asSharedFlow()

    private var workerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var activeTransport: VoiceGatewayTransport? = null

    private var endpoint: String = ""
    private var token: String = ""
    private var sessionId: String = ""
    private var serverId: String = ""
    private var userId: String = ""
    private var missedAcks: Int = 0
    private var heartbeatNonce: Long = 0
    private var ssrc: Int = 0

    @Volatile
    private var daveBinaryHandler: (suspend (op: Int, payload: ByteArray) -> Unit)? = null

    @Volatile
    private var daveJsonHandler: (suspend (op: Int, body: JsonElement) -> Unit)? = null

    override fun setDaveBinaryHandler(handler: suspend (op: Int, payload: ByteArray) -> Unit) {
        daveBinaryHandler = handler
    }

    override fun setDaveJsonHandler(handler: suspend (op: Int, body: JsonElement) -> Unit) {
        daveJsonHandler = handler
    }

    override suspend fun sendBinary(bytes: ByteArray) {
        val transport = activeTransport ?: run {
            Logger.w(TAG) { "DAVE sendBinary skipped: no active transport" }
            return
        }
        transport.sendBinary(bytes)
    }

    override suspend fun sendDaveJson(op: Int, jsonBody: String) {
        val transport = activeTransport ?: run {
            Logger.w(TAG) { "DAVE sendDaveJson skipped: no active transport" }
            return
        }
        val body = JSON.parseToJsonElement(jsonBody)
        val payload = buildJsonObject {
            put("op", JsonPrimitive(op))
            put("d", body)
        }
        transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
    }

    override suspend fun connect(
        endpoint: String,
        token: String,
        sessionId: String,
        serverId: String,
        userId: String,
    ) {
        if (workerJob?.isActive == true) return
        this.endpoint = endpoint
        this.token = token
        this.sessionId = sessionId
        this.serverId = serverId
        this.userId = userId
        workerJob = scope.launch { runWithReconnect() }
    }

    override suspend fun sendSelectProtocol(externalIp: String, externalPort: Int, mode: String) {
        val transport = activeTransport ?: run {
            Logger.w(TAG) { "SELECT_PROTOCOL skipped: no active transport" }
            return
        }
        val payload = buildJsonObject {
            put("op", JsonPrimitive(VoiceOp.SELECT_PROTOCOL))
            put(
                "d",
                JSON.encodeToJsonElement(
                    VoiceSelectProtocol.serializer(),
                    VoiceSelectProtocol(
                        protocol = "udp",
                        data = VoiceSelectProtocolData(
                            address = externalIp,
                            port = externalPort,
                            mode = mode,
                            codecs = DEFAULT_CODECS,
                        ),
                    ),
                ),
            )
        }
        transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
    }

    override suspend fun sendSpeaking(speaking: Int, ssrc: Int) {
        val transport = activeTransport ?: run {
            Logger.w(TAG) { "SPEAKING skipped: no active transport" }
            return
        }
        val payload = buildJsonObject {
            put("op", JsonPrimitive(VoiceOp.SPEAKING))
            put(
                "d",
                JSON.encodeToJsonElement(
                    VoiceSpeaking.serializer(),
                    VoiceSpeaking(speaking = speaking, delay = 0, ssrc = ssrc),
                ),
            )
        }
        transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
    }

    override suspend fun sendVideoStream(audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, active: Boolean) {
        val transport = activeTransport ?: run {
            Logger.w(TAG) { "VIDEO_STREAM skipped: no active transport" }
            return
        }
        val payload = buildJsonObject {
            put("op", JsonPrimitive(VoiceOp.VIDEO_STREAM))
            put(
                "d",
                JSON.encodeToJsonElement(
                    Op12VideoStream.serializer(),
                    Op12VideoStream(
                        audioSsrc = audioSsrc,
                        videoSsrc = videoSsrc,
                        rtxSsrc = rtxSsrc,
                        streams = listOf(
                            Op12VideoStream.StreamSpec(
                                ssrc = videoSsrc,
                                rtxSsrc = rtxSsrc,
                                active = active,
                            ),
                        ),
                    ),
                ),
            )
        }
        transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
    }

    override suspend fun close() {
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        activeTransport?.close()
        workerJob?.cancelAndJoin()
        workerJob = null
        activeTransport = null
        _state.value = VoiceGatewayState.Idle
    }

    private suspend fun runWithReconnect() {
        var attempt = 1
        while (true) {
            _state.value = VoiceGatewayState.ConnectingWs(attempt)
            val transport = try {
                transportFactory(endpoint)
            } catch (t: Throwable) {
                Logger.w(TAG) { "voice WS connect failed attempt=$attempt: ${t.message}" }
                if (attempt >= maxReconnectAttempts) {
                    _state.value = VoiceGatewayState.Failed("connect failed: ${t.message}")
                    return
                }
                _state.value = VoiceGatewayState.Reconnecting(attempt, "connect: ${t.message}")
                sleep(backoffMs(attempt))
                attempt++
                continue
            }
            activeTransport = transport
            _state.value = VoiceGatewayState.AwaitingHello
            missedAcks = 0

            val sessionTerminatedNormally = try {
                runSession(transport)
            } catch (t: Throwable) {
                Logger.w(TAG) { "voice session error: ${t.message}" }
                false
            } finally {
                heartbeatJob?.cancelAndJoin()
                heartbeatJob = null
                activeTransport = null
            }

            if (sessionTerminatedNormally) return
            if (attempt >= maxReconnectAttempts) {
                _state.value = VoiceGatewayState.Failed("max reconnect attempts reached")
                return
            }
            _state.value = VoiceGatewayState.Reconnecting(attempt, "session lost")
            sleep(backoffMs(attempt))
            attempt++
        }
    }

    /** @return true when the session was closed cleanly and no reconnect is needed. */
    private suspend fun runSession(transport: VoiceGatewayTransport): Boolean {
        var closedCleanly = false
        transport.incoming.collect { frame ->
            when (frame) {
                is VoiceFrameIn.Text -> handleText(transport, frame.text)
                is VoiceFrameIn.Binary -> handleBinary(frame.bytes)
                is VoiceFrameIn.Close -> {
                    Logger.i(TAG) { "voice WS closed code=${frame.code} reason=${frame.reason}" }
                    closedCleanly = frame.code == VoiceGatewayTransport.NORMAL_CLOSURE
                    return@collect
                }
            }
        }
        return closedCleanly
    }

    private suspend fun handleBinary(bytes: ByteArray) {
        // DAVE binary frame layout: (seq:u16 BE)(op:u8) || MLS payload bytes.
        if (bytes.size < DAVE_BINARY_HEADER_LEN) {
            Logger.w(TAG) { "DAVE binary frame too short: ${bytes.size}" }
            return
        }
        val op = bytes[2].toInt() and 0xFF
        val payload = bytes.copyOfRange(DAVE_BINARY_HEADER_LEN, bytes.size)
        daveBinaryHandler?.invoke(op, payload) ?: Logger.d(TAG) {
            "DAVE binary op=$op received but no handler installed"
        }
    }

    private suspend fun handleText(transport: VoiceGatewayTransport, text: String) {
        val envelope = JSON.decodeFromString(VoiceFrame.serializer(), text)
        // DAVE JSON ops (21-24, 31) — route to installed handler if any.
        if (envelope.op in VoiceOp.DAVE_JSON_MIN..VoiceOp.DAVE_JSON_MAX ||
            envelope.op == VoiceOp.DAVE_MLS_INVALID_COMMIT_WELCOME) {
            val body = envelope.d
            if (body != null) {
                daveJsonHandler?.invoke(envelope.op, body) ?: Logger.d(TAG) {
                    "DAVE json op=${envelope.op} received but no handler installed"
                }
            }
            return
        }
        when (envelope.op) {
            VoiceOp.HELLO -> {
                val hello = JSON.decodeFromJsonElement(VoiceHello.serializer(), requireD(envelope))
                sendIdentify(transport)
                _state.value = VoiceGatewayState.Identifying
                _state.value = VoiceGatewayState.AwaitingReady
                startHeartbeat(transport, hello.heartbeatInterval.toLong())
            }
            VoiceOp.READY -> {
                val ready = JSON.decodeFromJsonElement(VoiceReady.serializer(), requireD(envelope))
                ssrc = ready.ssrc
                _state.value = VoiceGatewayState.Active(ready.ssrc)
                _events.tryEmit(
                    VoiceGatewayEvent.Ready(
                        ssrc = ready.ssrc,
                        ip = ready.ip,
                        port = ready.port,
                        modes = ready.modes,
                        videoSsrc = ready.videoSsrc,
                    ),
                )
            }
            VoiceOp.SESSION_DESCRIPTION -> {
                val sd = JSON.decodeFromJsonElement(VoiceSessionDescription.serializer(), requireD(envelope))
                val key = ByteArray(sd.secretKey.size) { sd.secretKey[it].toByte() }
                _events.tryEmit(
                    VoiceGatewayEvent.SessionDescription(
                        mode = sd.mode,
                        secretKey = key,
                        daveProtocolVersion = sd.daveProtocolVersion ?: 0,
                        videoCodec = sd.videoCodec,
                    ),
                )
            }
            VoiceOp.SPEAKING -> {
                val sp = JSON.decodeFromJsonElement(VoiceSpeaking.serializer(), requireD(envelope))
                val uid = sp.userId ?: return
                _events.tryEmit(VoiceGatewayEvent.Speaking(uid, sp.ssrc, sp.speaking))
            }
            VoiceOp.HEARTBEAT_ACK -> {
                missedAcks = 0
            }
            VoiceOp.CLIENT_DISCONNECT -> {
                val cd = JSON.decodeFromJsonElement(VoiceClientDisconnect.serializer(), requireD(envelope))
                _events.tryEmit(VoiceGatewayEvent.ClientDisconnect(cd.userId))
            }
            VoiceOp.RESUMED -> {
                // resume ok — session restored, nothing to do beyond logging.
                Logger.i(TAG) { "voice gateway resumed" }
            }
            else -> {
                Logger.d(TAG) { "voice unhandled op=${envelope.op}" }
            }
        }
    }

    private suspend fun sendIdentify(transport: VoiceGatewayTransport) {
        val payload = buildJsonObject {
            put("op", JsonPrimitive(VoiceOp.IDENTIFY))
            put(
                "d",
                JSON.encodeToJsonElement(
                    VoiceIdentify.serializer(),
                    VoiceIdentify(
                        serverId = serverId,
                        userId = userId,
                        sessionId = sessionId,
                        token = token,
                    ),
                ),
            )
        }
        transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
    }

    private fun startHeartbeat(transport: VoiceGatewayTransport, intervalMs: Long) {
        heartbeatJob?.let { scope.launch { it.cancelAndJoin() } }
        heartbeatJob = scope.launch {
            while (true) {
                sleep(intervalMs)
                if (missedAcks >= maxMissedAcks) {
                    Logger.w(TAG) { "missed $missedAcks heartbeat acks — forcing reconnect" }
                    transport.close(code = HEARTBEAT_TIMEOUT_CODE, reason = "missed acks")
                    return@launch
                }
                heartbeatNonce = if (heartbeatNonce == Long.MAX_VALUE) 1 else heartbeatNonce + 1
                missedAcks++
                val nonce = heartbeatNonce
                val payload = buildJsonObject {
                    put("op", JsonPrimitive(VoiceOp.HEARTBEAT))
                    put("d", JsonPrimitive(nonce))
                }
                transport.sendText(JSON.encodeToString(JsonElement.serializer(), payload))
                now()
            }
        }
    }

    private fun requireD(envelope: VoiceFrame): JsonElement =
        envelope.d ?: error("voice frame op=${envelope.op} missing required `d` field")

    private fun backoffMs(attempt: Int): Long {
        val capped = attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)
        return BASE_BACKOFF_MS shl (capped - 1)
    }

    private companion object {
        const val TAG = "VoiceGatewayConnection"
        const val EVENT_BUFFER = 64
        const val DEFAULT_MAX_MISSED_ACKS = 2
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_EXPONENT = 4 // caps at 8 s (1<<3)
        const val HEARTBEAT_TIMEOUT_CODE = 4009
        const val DAVE_BINARY_HEADER_LEN = 3
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // Codec advertisement sent in Op 1 SelectProtocol. Audio = encode+decode (we send and
        // receive voice); video = encode only (4.0 MVP doesn't render remote video).
        // See architect report `2026-05-23-screenshare.md` §4.
        val DEFAULT_CODECS = listOf(
            VoiceCodecEntry(
                name = "opus",
                type = "audio",
                payloadType = 120,
                rtxPayloadType = null,
                priority = 1000,
                encode = true,
                decode = true,
            ),
            VoiceCodecEntry(
                name = "H264",
                type = "video",
                payloadType = 101,
                rtxPayloadType = 102,
                priority = 1000,
                encode = true,
                decode = false,
            ),
            VoiceCodecEntry(
                name = "VP8",
                type = "video",
                payloadType = 103,
                rtxPayloadType = 104,
                priority = 2000,
                encode = true,
                decode = false,
            ),
        )
    }
}
