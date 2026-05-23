package dev.puklic.voice

import co.touchlab.kermit.Logger
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.audio.audioCapture
import dev.puklic.voice.audio.audioPlayback
import dev.puklic.voice.audio.listAudioDevices
import dev.puklic.voice.codec.OpusCodecFactory
import dev.puklic.voice.crypto.xchacha20Poly1305
import dev.puklic.voice.gateway.DefaultVoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayEvent
import dev.puklic.voice.gateway.VoiceWsTransportFactory
import dev.puklic.voice.pipeline.CapturePipeline
import dev.puklic.voice.pipeline.PlaybackPipeline
import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.VoicePacketCodec
import dev.puklic.voice.transport.discoverIp
import dev.puklic.voice.transport.newUdpRtpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Slim bridge so [DefaultVoiceClient] doesn't depend on `:shared:protocol-discord` or
 * `:shared:repositories`. The wiring layer (DependencyGraph) adapts the main gateway +
 * GatewayEventSource to this interface.
 */
public interface MainGatewayBridge {
    /** Send OP 4 VOICE_STATE_UPDATE on the main gateway. */
    public suspend fun sendVoiceStateUpdate(
        guildId: GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    )

    /** Stream of `VOICE_STATE_UPDATE` dispatches (server-side echo + session id). */
    public val voiceStateUpdates: SharedFlow<VoiceStateUpdate>

    /** Stream of `VOICE_SERVER_UPDATE` dispatches (endpoint + token). */
    public val voiceServerUpdates: SharedFlow<VoiceServerUpdate>

    public data class VoiceStateUpdate(
        val guildId: GuildId?,
        val channelId: ChannelId?,
        val userId: UserId,
        val sessionId: String,
    )

    public data class VoiceServerUpdate(
        val guildId: GuildId,
        val token: String,
        val endpoint: String?,
    )
}

/**
 * Default [VoiceClient] orchestrating the full pipeline:
 *
 *  1. send OP 4 VOICE_STATE_UPDATE on main gateway
 *  2. await VOICE_STATE_UPDATE + VOICE_SERVER_UPDATE (session id + endpoint + token)
 *  3. open voice WS, handshake to Active
 *  4. on Ready → UDP IP discovery → SelectProtocol
 *  5. on SessionDescription → start capture + playback pipelines
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §5, §11, §12.
 */
@Suppress("LongParameterList")
public class DefaultVoiceClient(
    private val applicationScope: CoroutineScope,
    private val mainGateway: MainGatewayBridge,
    private val selfUserIdProvider: () -> UserId?,
    private val voiceTransportFactory: VoiceWsTransportFactory,
) : VoiceClient {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    private val mutex = Mutex()

    // Active session resources — null when Idle.
    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var voiceGateway: VoiceGatewayConnection? = null
    private var udp: UdpRtpTransport? = null
    private var capture: CapturePipeline? = null
    private var playback: PlaybackPipeline? = null
    private var packetCodec: VoicePacketCodec? = null

    private var activeGuildId: GuildId? = null
    private var activeChannelId: ChannelId? = null
    private var activeSsrc: Int = 0

    // SSRC ↔ UserId resolver, populated from Op 5 Speaking server events.
    private val ssrcToUser = ConcurrentHashMap<Int, UserId>()

    init {
        refreshDevices()
    }

    private fun refreshDevices() {
        val merged = listAudioDevices(AudioDevice.Direction.Capture) +
            listAudioDevices(AudioDevice.Direction.Playback)
        _devices.value = merged
    }

    override suspend fun connect(guildId: GuildId, channelId: ChannelId): Unit = mutex.withLock {
        if (_state.value !is VoiceState.Idle && _state.value !is VoiceState.Failed) {
            Logger.w(TAG) { "connect() ignored: state=${_state.value}" }
            return
        }
        refreshDevices()
        _state.value = VoiceState.Connecting(channelId, attempt = 1)
        activeGuildId = guildId
        activeChannelId = channelId
        val selfId = selfUserIdProvider() ?: run {
            fail("self user id not available")
            return
        }

        val sJob = SupervisorJob(applicationScope.coroutineContext[Job])
        val sScope = CoroutineScope(applicationScope.coroutineContext + sJob + Dispatchers.Default)
        sessionJob = sJob
        sessionScope = sScope

        sScope.launch {
            runCatching { runConnect(guildId, channelId, selfId) }
                .onFailure { t ->
                    Logger.w(TAG) { "voice connect failed: ${t.message}" }
                    fail(t.message ?: t::class.simpleName.orEmpty())
                    cleanup()
                }
        }
    }

    private suspend fun runConnect(guildId: GuildId, channelId: ChannelId, selfId: UserId) {
        // Send Op 4 — server will reply with VOICE_STATE_UPDATE + VOICE_SERVER_UPDATE.
        mainGateway.sendVoiceStateUpdate(guildId, channelId, selfMute = false, selfDeaf = false)

        val stateUpdate = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            mainGateway.voiceStateUpdates
                .filterIsInstance<MainGatewayBridge.VoiceStateUpdate>()
                .first { it.userId == selfId && it.guildId == guildId && it.channelId == channelId }
        } ?: error("timed out waiting for VOICE_STATE_UPDATE")

        val serverUpdate = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            mainGateway.voiceServerUpdates.first { it.guildId == guildId && !it.endpoint.isNullOrBlank() }
        } ?: error("timed out waiting for VOICE_SERVER_UPDATE")

        val endpointHost = serverUpdate.endpoint!!.substringBefore(":")
        val wsUrl = "wss://${serverUpdate.endpoint}/?v=8"
        val scope = checkNotNull(sessionScope) { "session scope null" }

        val gw = DefaultVoiceGatewayConnection(scope = scope, transportFactory = voiceTransportFactory.delegate)
        voiceGateway = gw
        scope.launch { collectVoiceGatewayEvents(gw, endpointHost) }
        gw.connect(
            endpoint = wsUrl,
            token = serverUpdate.token,
            sessionId = stateUpdate.sessionId,
            serverId = guildId.value.toString(),
            userId = selfId.value.toString(),
        )
    }

    private suspend fun collectVoiceGatewayEvents(gw: VoiceGatewayConnection, endpointHost: String) {
        var ssrc = 0
        var aeadKey: ByteArray? = null
        var udpReady = false
        gw.events.collect { ev ->
            when (ev) {
                is VoiceGatewayEvent.Ready -> {
                    ssrc = ev.ssrc
                    activeSsrc = ssrc
                    runCatching { openUdpAndDiscover(gw, ev.ip, ev.port, ssrc, endpointHost) }
                        .onSuccess { udpReady = true }
                        .onFailure { t ->
                            Logger.w(TAG) { "UDP discovery failed: ${t.message}" }
                            fail("UDP discovery failed: ${t.message}")
                        }
                }
                is VoiceGatewayEvent.SessionDescription -> {
                    aeadKey = ev.secretKey
                    if (udpReady) {
                        startPipelines(ssrc, ev.secretKey)
                        emitConnected()
                    }
                }
                is VoiceGatewayEvent.Speaking -> {
                    runCatching {
                        val uid = UserId(ev.userId.toLong())
                        ssrcToUser[ev.ssrc] = uid
                    }
                }
                is VoiceGatewayEvent.ClientDisconnect -> {
                    // Remove any SSRC mapped to this user (cheap linear scan; user count tiny).
                    runCatching {
                        val uid = UserId(ev.userId.toLong())
                        ssrcToUser.entries.removeAll { it.value == uid }
                    }
                }
            }
        }
    }

    private suspend fun openUdpAndDiscover(
        gw: VoiceGatewayConnection,
        host: String,
        port: Int,
        ssrc: Int,
        @Suppress("UNUSED_PARAMETER") endpointHost: String,
    ) {
        val t = newUdpRtpTransport()
        udp = t
        t.bind()
        t.connect(host, port)
        val result = t.discoverIp(ssrc)
        gw.sendSelectProtocol(result.address, result.port, mode = AEAD_MODE)
    }

    private fun startPipelines(ssrc: Int, secretKey: ByteArray) {
        val scope = checkNotNull(sessionScope)
        val udpT = checkNotNull(udp)
        val aead = xchacha20Poly1305(secretKey)
        val codec = VoicePacketCodec(aead = aead, ssrc = ssrc)
        packetCodec = codec

        val encoder = OpusCodecFactory.createEncoder()
        val cap = CapturePipeline(
            capture = audioCapture(),
            encoder = encoder,
            encodeAndSend = { opus ->
                val packet = codec.encode(opus)
                udpT.send(packet)
            },
            onSpeakingChange = { speaking ->
                voiceGateway?.sendSpeaking(if (speaking) 1 else 0, ssrc)
            },
        )
        val play = PlaybackPipeline(
            transport = udpT,
            packetCodec = codec,
            decoderFactory = { OpusCodecFactory.createDecoder() },
            playback = audioPlayback(),
            onSpeakers = { activeSsrcs ->
                updateSpeakers(activeSsrcs)
            },
        )
        capture = cap
        playback = play
        cap.start(scope)
        play.start(scope)
    }

    private fun updateSpeakers(activeSsrcs: Set<Int>) {
        val current = _state.value
        if (current !is VoiceState.Connected) return
        val users = activeSsrcs.mapNotNull { ssrcToUser[it] }.toSet()
        if (users == current.speakers) return
        _state.value = current.copy(speakers = users)
    }

    private fun emitConnected() {
        val ch = activeChannelId ?: return
        _state.value = VoiceState.Connected(
            channelId = ch,
            ssrc = activeSsrc,
            selfMute = false,
            selfDeaf = false,
            speakers = emptySet(),
        )
    }

    override suspend fun disconnect(): Unit = mutex.withLock {
        if (_state.value is VoiceState.Idle) return
        val guildId = activeGuildId
        runCatching { capture?.stop() }
        runCatching { playback?.stop() }
        runCatching { voiceGateway?.close() }
        runCatching { udp?.close() }
        if (guildId != null) {
            runCatching {
                mainGateway.sendVoiceStateUpdate(
                    guildId = guildId,
                    channelId = null,
                    selfMute = false,
                    selfDeaf = false,
                )
            }
        }
        cleanup()
        _state.value = VoiceState.Idle
    }

    private fun cleanup() {
        capture = null
        playback = null
        voiceGateway = null
        udp = null
        packetCodec = null
        activeGuildId = null
        activeChannelId = null
        ssrcToUser.clear()
        sessionScope?.cancel()
        sessionScope = null
        sessionJob = null
    }

    override fun setSelfMute(muted: Boolean) {
        val current = _state.value as? VoiceState.Connected ?: return
        _state.value = current.copy(selfMute = muted, selfDeaf = current.selfDeaf)
        applicationScope.launch {
            runCatching {
                mainGateway.sendVoiceStateUpdate(
                    guildId = activeGuildId,
                    channelId = activeChannelId,
                    selfMute = muted,
                    selfDeaf = current.selfDeaf,
                )
            }
            runCatching { voiceGateway?.sendSpeaking(if (muted) 0 else 1, activeSsrc) }
            // Pause/resume capture by stopping the loop. Restart-on-unmute keeps things simple.
            if (muted) {
                capture?.stop()
            } else {
                val scope = sessionScope
                if (scope != null && capture == null) {
                    // Pipeline was destroyed; we would need to rebuild. For now this is a no-op:
                    // mute always tears down only the capture loop, never the encoder/codec.
                }
            }
        }
    }

    override fun setSelfDeaf(deaf: Boolean) {
        val current = _state.value as? VoiceState.Connected ?: return
        // Deaf implies mute per Discord UI convention.
        val mute = deaf || current.selfMute
        _state.value = current.copy(selfDeaf = deaf, selfMute = mute)
        applicationScope.launch {
            runCatching {
                mainGateway.sendVoiceStateUpdate(
                    guildId = activeGuildId,
                    channelId = activeChannelId,
                    selfMute = mute,
                    selfDeaf = deaf,
                )
            }
            if (deaf) {
                playback?.stop()
                capture?.stop()
            }
        }
    }

    override fun selectCaptureDevice(id: String) {
        // Simplest implementation: tear down + rebuild on next connect. For an active session,
        // we would have to swap the AudioCapture instance — deferred (see report §10).
        refreshDevices()
    }

    override fun selectPlaybackDevice(id: String) {
        refreshDevices()
    }

    private fun fail(reason: String) {
        _state.value = VoiceState.Failed(reason = reason, recoverable = true)
    }

    private companion object {
        const val TAG = "DefaultVoiceClient"
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val AEAD_MODE = "aead_xchacha20_poly1305_rtpsize"
    }
}
