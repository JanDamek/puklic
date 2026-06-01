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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
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
    /** Abnormal closure (WebSocket spec 1006) — TCP/TLS reset, network drop. */
    const val ABNORMAL = 1006
    /** Client-initiated when we detect a zombied connection (no heartbeat ACK). */
    const val ZOMBIED = 4000
}

/**
 * Discord gateway client — state machine driven by an injected [GatewayTransportFactory] so the
 * full lifecycle (HELLO → IDENTIFY → READY, heartbeat with ACK tracking, zombied-connection
 * detection, auto-reconnect with RESUME on the resume_gateway_url, close-code dispatch) is
 * testable without any live network. Tokens are never logged.
 *
 * Resilience design — see `docs/03_infrastructure/architect-reports/2026-06-01-issue-82-dm-sync-robustness.md`:
 *  - the supervisor loop in [runLoop] reconnects automatically on every close path except
 *    [CloseCode.AUTH_FAILED] (which is terminal) and explicit [disconnect];
 *  - between attempts an exponential backoff (1, 2, 4, 8, 16, 30 s capped) clamps retries;
 *  - the client heartbeat is fully autonomous — it ticks every `heartbeat_interval` ms from
 *    HELLO, tracks last sent vs last ACK, and triggers a close-and-reconnect when an ACK is
 *    missed (zombied connection);
 *  - resumable closes keep `sessionId` + `sequence` and reconnect via `resume_gateway_url`,
 *    sending OP 6 RESUME on next HELLO; non-resumable closes (or OP 9 INVALID_SESSION) clear
 *    them and the next connect sends a fresh OP 2 IDENTIFY.
 */
public class GatewayConnection(
    private val scope: CoroutineScope,
    private val token: String,
    private val transportFactory: GatewayTransportFactory,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    clientProperties: DiscordClientProperties = buildClientProperties(),
    /**
     * Multiplier applied to the server-supplied `heartbeat_interval`. Default 1.0 (spec). Tests
     * can override to compress real-time intervals. The jitter for the first heartbeat is a
     * fixed fraction of the interval, see [FIRST_HEARTBEAT_JITTER_FRACTION].
     */
    private val heartbeatIntervalScale: Double = 1.0,
    /**
     * Backoff sequence (ms) used between reconnect attempts. Default per design report
     * (1, 2, 4, 8, 16, 30 s capped). Tests can override with [0L] for instant retries.
     */
    private val reconnectBackoffMs: LongArray = DEFAULT_RECONNECT_BACKOFF_MS,
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
    private var heartbeatJob: Job? = null

    // Heartbeat ACK tracking — incremented on every send, set equal on every ACK. If the
    // pre-tick count differs from `lastHeartbeatAcked` before the next send, the connection
    // is presumed zombied.
    private var lastHeartbeatSent: Long = 0
    private var lastHeartbeatAcked: Long = 0

    public fun connect(url: String = GATEWAY_URL_DEFAULT) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runLoop(url) }
    }

    public suspend fun disconnect() {
        workerJob?.cancelAndJoin()
        workerJob = null
        heartbeatJob?.cancelAndJoin()
        heartbeatJob = null
        activeTransport = null
        _state.value = GatewayState.Disconnected
    }

    /**
     * Supervisor loop — opens a transport, drives the lifecycle, and on close decides whether
     * to reconnect (preserving or clearing session state per close code) or terminate.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun runLoop(initialUrl: String) {
        var attempt = 0
        var nextUrl = initialUrl
        while (currentCoroutineContext().isActive) {
            _state.value = GatewayState.Connecting(attemptCount = attempt + 1)
            val transport = try {
                transportFactory(nextUrl)
            } catch (cause: kotlinx.coroutines.CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                Logger.w("GatewayConnection") {
                    "transportFactory threw: ${cause::class.simpleName} msg=${cause.message?.take(200)}"
                }
                attempt = (attempt + 1).coerceAtMost(reconnectBackoffMs.size - 1)
                delayBackoff(attempt)
                continue
            }
            activeTransport = transport
            // Reset heartbeat counters for the new connection.
            lastHeartbeatSent = 0
            lastHeartbeatAcked = 0
            var closeCode: Int = CloseCode.ABNORMAL
            try {
                transport.incoming.collect { frame ->
                    when (frame) {
                        is GatewayFrameIn.Text -> handleText(transport, frame.text)
                        is GatewayFrameIn.Binary -> {
                            // Gateway is configured with `encoding=json` (text frames only);
                            // binary frames are reserved for the zlib-stream / etf variants that
                            // this client does not negotiate. Ignore.
                        }
                        is GatewayFrameIn.Close -> {
                            closeCode = frame.code
                            handleClose(frame.code)
                            throw CloseSignal
                        }
                    }
                }
            } catch (_: CloseSignal) {
                // Graceful exit from frame collection on Close.
            } catch (cause: kotlinx.coroutines.CancellationException) {
                heartbeatJob?.cancelAndJoin()
                heartbeatJob = null
                activeTransport = null
                throw cause
            } catch (cause: Throwable) {
                Logger.w("GatewayConnection") {
                    "transport incoming threw: ${cause::class.simpleName} msg=${cause.message?.take(200)}"
                }
                closeCode = CloseCode.ABNORMAL
                handleClose(closeCode)
            } finally {
                heartbeatJob?.cancelAndJoin()
                heartbeatJob = null
                activeTransport = null
            }

            // Decide what to do next based on the close code and the current state.
            if (_state.value is GatewayState.TokenInvalid) {
                // Terminal — never retry on bad auth.
                return
            }
            attempt = (attempt + 1).coerceAtMost(reconnectBackoffMs.size - 1)
            // INVALID_SESSION → fresh IDENTIFY against default gateway.
            // Resumable close → RESUME via resume_gateway_url.
            // Anything else → fresh IDENTIFY against default gateway.
            val resumable = sessionId != null && isResumableCloseCode(closeCode)
            nextUrl = if (resumable) resumeGatewayUrl ?: GATEWAY_URL_DEFAULT else GATEWAY_URL_DEFAULT
            delayBackoff(attempt)
        }
    }

    private suspend fun delayBackoff(attemptIndex: Int) {
        val idx = attemptIndex.coerceIn(0, reconnectBackoffMs.size - 1)
        val ms = reconnectBackoffMs[idx]
        if (ms > 0L) delay(ms)
    }

    private fun isResumableCloseCode(code: Int): Boolean = when (code) {
        CloseCode.UNKNOWN_ERROR,
        CloseCode.INVALID_SEQ,
        CloseCode.RATE_LIMITED,
        CloseCode.SESSION_TIMEOUT,
        CloseCode.ABNORMAL,
        GatewayTransport.NORMAL_CLOSURE,
        -> true
        else -> false
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

    /**
     * Send OP 4 `VOICE_STATE_UPDATE` on the **main** gateway. This is step 1 of the voice trigger
     * flow per `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5: the client picks
     * a voice channel, the gateway responds with VOICE_STATE_UPDATE (session_id) and
     * VOICE_SERVER_UPDATE (token, endpoint), and the voice WS connection can then be opened.
     *
     * JSON shape:
     *   {"op": 4, "d": {"guild_id": "<id>"|null, "channel_id": "<id>"|null,
     *                   "self_mute": false, "self_deaf": false}}
     *
     * Passing `channelId = null` signals leaving the current voice channel.
     * `guildId = null` is reserved for DM-call voice (not implemented in Phase 3.0); pass-through.
     * No-ops if the gateway is not currently connected.
     */
    public suspend fun sendVoiceStateUpdate(
        guildId: GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    ) {
        val transport = activeTransport
        if (transport == null) {
            Logger.w("GatewayConnection") {
                "OP4 VOICE_STATE_UPDATE skipped: no active transport " +
                    "(guild=${guildId?.value} channel=${channelId?.value})"
            }
            return
        }
        Logger.i("GatewayConnection") {
            "OP4 VOICE_STATE_UPDATE sending guild=${guildId?.value} channel=${channelId?.value} " +
                "selfMute=$selfMute selfDeaf=$selfDeaf"
        }
        val payload = buildJsonObject {
            put("op", JsonPrimitive(Opcode.VOICE_STATE_UPDATE))
            putJsonObject("d") {
                put(
                    "guild_id",
                    guildId?.let { JsonPrimitive(it.value.toString()) } ?: JsonPrimitive(null as String?),
                )
                put(
                    "channel_id",
                    channelId?.let { JsonPrimitive(it.value.toString()) } ?: JsonPrimitive(null as String?),
                )
                put("self_mute", JsonPrimitive(selfMute))
                put("self_deaf", JsonPrimitive(selfDeaf))
            }
        }
        transport.sendText(DiscordJson.encodeToString(JsonElement.serializer(), payload))
        Logger.i("GatewayConnection") { "OP4 VOICE_STATE_UPDATE sent" }
    }

    private suspend fun handleText(transport: GatewayTransport, text: String) {
        val envelope = DiscordJson.decodeFromString(GatewayFrame.serializer(), text)
        envelope.s?.let { sequence = it }
        when (envelope.op) {
            Opcode.HELLO -> {
                val hello = DiscordJson.decodeFromJsonElement(GatewayHello.serializer(), requireD(envelope))
                _state.value = GatewayState.HelloReceived(hello.heartbeatInterval)
                startHeartbeatLoop(transport, hello.heartbeatInterval)
                sendIdentify(transport)
                _state.value = GatewayState.Identifying(hello.heartbeatInterval)
            }
            Opcode.HEARTBEAT_ACK -> {
                lastHeartbeatAcked = lastHeartbeatSent
            }
            Opcode.HEARTBEAT -> {
                // Server-requested heartbeat — reply immediately with current sequence. Does not
                // disturb the autonomous heartbeat timer (which keeps ticking independently).
                sendHeartbeat(transport)
            }
            Opcode.INVALID_SESSION -> {
                // Session invalidated; non-resumable. Per Discord docs the client should wait a
                // random 1-5 s before re-IDENTIFYing; the reconnect-backoff after socket close
                // serves that purpose. Drop session state so the next connect uses IDENTIFY.
                sessionId = null
                sequence = 0
                resumeGatewayUrl = null
                transport.close(code = GatewayTransport.NORMAL_CLOSURE, reason = "invalid session, re-identifying")
            }
            Opcode.DISPATCH -> handleDispatch(envelope)
            Opcode.RECONNECT -> {
                // Server requesting reconnect — close cleanly; the supervisor loop reconnects
                // via resume_gateway_url with OP 6 RESUME.
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
            CloseCode.AUTH_FAILED -> {
                // Terminal — drop session state so a future explicit re-login does not try
                // to reuse a session id that is associated with a rejected token.
                sessionId = null
                sequence = 0
                resumeGatewayUrl = null
                GatewayState.TokenInvalid
            }
            CloseCode.INVALID_SEQ,
            CloseCode.SESSION_TIMEOUT,
            CloseCode.RATE_LIMITED,
            CloseCode.UNKNOWN_ERROR,
            CloseCode.ABNORMAL,
            GatewayTransport.NORMAL_CLOSURE,
            -> sessionId?.let { GatewayState.Resuming(sessionId = it, sequence = sequence) }
                ?: GatewayState.Disconnected
            else -> {
                // Non-resumable per spec — drop session and re-identify next round.
                sessionId = null
                sequence = 0
                resumeGatewayUrl = null
                GatewayState.Disconnected
            }
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
        lastHeartbeatSent += 1
        // Touch `now` to keep the clock injected for future jitter logic.
        now()
    }

    /**
     * Launch (or restart) the autonomous client-side heartbeat loop. Cancels any prior loop
     * (defensive — a fresh transport always starts a fresh HELLO). The first beat is sent
     * after `interval * FIRST_HEARTBEAT_JITTER_FRACTION` per Discord docs; subsequent beats
     * tick at the full interval. If the previous beat was not ACKed by the time the next
     * tick fires, the connection is closed with [CloseCode.ZOMBIED] to trigger a reconnect.
     */
    private fun startHeartbeatLoop(transport: GatewayTransport, intervalMs: Long) {
        heartbeatJob?.cancel()
        val scaled = (intervalMs * heartbeatIntervalScale).toLong().coerceAtLeast(1L)
        heartbeatJob = scope.launch {
            // First beat: jittered fraction of the interval per Discord spec.
            delay((scaled * FIRST_HEARTBEAT_JITTER_FRACTION).toLong().coerceAtLeast(0L))
            while (currentCoroutineContext().isActive) {
                // Detect zombied connection BEFORE sending the next beat — if the prior beat
                // was never ACKed, close and let the supervisor loop reconnect.
                if (lastHeartbeatSent > 0 && lastHeartbeatAcked < lastHeartbeatSent) {
                    Logger.w("GatewayConnection") {
                        "zombied connection detected (sent=$lastHeartbeatSent acked=$lastHeartbeatAcked) — closing"
                    }
                    runCatching {
                        transport.close(code = CloseCode.ZOMBIED, reason = "no heartbeat ACK")
                    }
                    return@launch
                }
                runCatching { sendHeartbeat(transport) }
                    .onFailure {
                        Logger.w("GatewayConnection") { "heartbeat send failed: ${it::class.simpleName}" }
                        return@launch
                    }
                delay(scaled)
            }
        }
    }

    private fun requireD(envelope: GatewayFrame): JsonElement =
        envelope.d ?: error("Gateway frame op=${envelope.op} missing required `d` field")

    @Suppress("UnusedPrivateMember")
    private fun firstSequenceFromPrimitive(primitive: JsonPrimitive?): Int? =
        primitive?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

    /** Sentinel exception used to break out of `flow.collect` when a Close frame arrives. */
    private object CloseSignal : RuntimeException("close-signal") {
        // No `fillInStackTrace` override: Kotlin/Native's Throwable doesn't expose it as
        // overridable. The sentinel is thrown + caught on the same call stack so the missing
        // stack-trace optimisation isn't material.
        private fun readResolve(): Any = CloseSignal
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val GUILD_SUBSCRIPTIONS_BULK_OPCODE = 37
        const val LAZY_RANGE_START = 0
        const val LAZY_RANGE_END = 99
        /** First heartbeat is sent after `interval * fraction` per Discord docs. */
        const val FIRST_HEARTBEAT_JITTER_FRACTION = 0.5
        /** Default reconnect backoff ladder in ms: 1, 2, 4, 8, 16, 30 s capped. */
        val DEFAULT_RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
