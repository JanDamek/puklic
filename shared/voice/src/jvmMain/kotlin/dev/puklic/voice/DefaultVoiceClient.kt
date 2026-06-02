package dev.puklic.voice

import co.touchlab.kermit.Logger
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.audio.audioCapture
import dev.puklic.voice.audio.audioPlayback
import dev.puklic.voice.audio.listAudioDevices
import dev.puklic.voice.codec.LibavH264DecoderFactory
import dev.puklic.voice.codec.OpusCodecFactory
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.crypto.xchacha20Poly1305
import dev.puklic.voice.dave.DaveSession
import dev.puklic.voice.dave.FrameDecryptor
import dev.puklic.voice.dave.FrameEncryptor
import dev.puklic.voice.dave.gateway.DaveBinaryFrame
import dev.puklic.voice.dave.mlsClient
import dev.puklic.voice.screenshare.DefaultScreenShareClient
import dev.puklic.voice.screenshare.NoOpScreenShareClient
import dev.puklic.voice.screenshare.ScreenShareClient
import dev.puklic.voice.screenshare.encoder.VideoCodec
import dev.puklic.voice.screenshare.encoder.chooseCodec
import dev.puklic.voice.screenshare.source.screenSourceEnumerator
import dev.puklic.voice.gateway.DefaultVoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayEvent
import dev.puklic.voice.gateway.VoiceWsTransportFactory
import dev.puklic.voice.pipeline.CapturePipeline
import dev.puklic.voice.pipeline.IncomingVideoPipeline
import dev.puklic.voice.pipeline.PlaybackPipeline
import dev.puklic.voice.codec.transport.Endpoint
import dev.puklic.voice.codec.transport.JvmVoiceUdpTransportFactory
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.transport.VoicePacketCodec
import dev.puklic.voice.transport.VoicePacketDispatcher
import dev.puklic.voice.transport.discoverIp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * DM incoming-call dispatches (CALL_CREATE / CALL_UPDATE / CALL_DELETE). See
     * architect-report 2026-05-25-dm-incoming-voice §6.
     */
    public val callEvents: SharedFlow<CallEvent>

    /**
     * Stop ringing recipients of a DM call. Used by [VoiceClient.declineIncoming]. The bridge
     * implementation MUST treat 404 / 410 as success (call already gone).
     */
    public suspend fun stopRinging(channelId: ChannelId, recipients: List<UserId>)

    /**
     * Best-effort resolve of a message's author id — used for the caller-id fallback chain
     * step 2 (architect-report 2026-05-25-dm-incoming-voice §4). Returns null on failure.
     */
    public suspend fun resolveMessageAuthor(channelId: ChannelId, messageId: dev.puklic.ids.MessageId): UserId?

    public data class VoiceStateUpdate(
        val guildId: GuildId?,
        val channelId: ChannelId?,
        val userId: UserId,
        val sessionId: String,
    )

    public data class VoiceServerUpdate(
        val guildId: GuildId?,
        val token: String,
        val endpoint: String?,
    )

    /** Tagged union of the three DM-call dispatches the voice layer cares about. */
    public sealed interface CallEvent {
        public val channelId: ChannelId

        public data class Started(
            override val channelId: ChannelId,
            val callerId: UserId?,
            val messageId: dev.puklic.ids.MessageId?,
            val ringing: Set<UserId>,
        ) : CallEvent

        public data class RingingUpdated(
            override val channelId: ChannelId,
            val ringing: Set<UserId>,
        ) : CallEvent

        public data class Ended(
            override val channelId: ChannelId,
            val unavailable: Boolean,
        ) : CallEvent
    }
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

    private val _incomingVideo = MutableStateFlow<Map<Int, IncomingVideoFrame>>(emptyMap())
    override val incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>> = _incomingVideo.asStateFlow()

    private val _daveState = MutableStateFlow<DaveUiState>(DaveUiState.Off)
    override val daveState: StateFlow<DaveUiState> = _daveState.asStateFlow()

    private val _incomingCalls = MutableStateFlow<List<IncomingCall>>(emptyList())
    override val incomingCalls: StateFlow<List<IncomingCall>> = _incomingCalls.asStateFlow()

    private val _participants = MutableStateFlow<Set<UserId>>(emptySet())
    override val participants: StateFlow<Set<UserId>> = _participants.asStateFlow()

    // Replaced with a [DefaultScreenShareClient] once SessionDescription arrives, reset to
    // [NoOpScreenShareClient] on disconnect. Backing field is `@Volatile` because the
    // gateway event collector writes it from a session coroutine while UI reads from main.
    @Volatile
    private var screenShareImpl: ScreenShareClient = NoOpScreenShareClient()
    override val screenShare: ScreenShareClient
        get() = screenShareImpl

    private val mutex = Mutex()

    // Active session resources — null when Idle.
    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var voiceGateway: VoiceGatewayConnection? = null
    private var udp: VoiceUdpTransport? = null
    private var capture: CapturePipeline? = null
    private var playback: PlaybackPipeline? = null
    private var incomingVideoPipeline: IncomingVideoPipeline? = null
    private var packetDispatcher: VoicePacketDispatcher? = null
    private var packetCodec: VoicePacketCodec? = null
    private var videoPacketCodec: VoicePacketCodec? = null
    private var videoFramesCollectorJob: Job? = null

    private var activeGuildId: GuildId? = null
    private var activeChannelId: ChannelId? = null
    private var activeSsrc: Int = 0
    private var activeVideoSsrc: Int = 0

    private var daveSession: DaveSession? = null
    private val daveSeq = AtomicInteger(0)
    private var daveStateCollectorJob: Job? = null
    private val daveEncryptors = ConcurrentHashMap<Int, FrameEncryptor>()
    private val daveDecryptors = ConcurrentHashMap<Long, FrameDecryptor>()

    // SSRC ↔ UserId resolver, populated from Op 5 Speaking server events.
    private val ssrcToUser = ConcurrentHashMap<Int, UserId>()

    init {
        refreshDevices()
        // Subscribe to incoming-call dispatches on the application scope (queue must outlive
        // any single voice session — calls can arrive while [state] is Idle).
        applicationScope.launch {
            mainGateway.callEvents.collect { ev -> handleCallEvent(ev) }
        }
    }

    private suspend fun handleCallEvent(ev: MainGatewayBridge.CallEvent) {
        when (ev) {
            is MainGatewayBridge.CallEvent.Started -> handleCallStarted(ev)
            is MainGatewayBridge.CallEvent.RingingUpdated -> {
                val selfId = selfUserIdProvider() ?: return
                if (selfId !in ev.ringing) removeFromIncomingQueue(ev.channelId)
            }
            is MainGatewayBridge.CallEvent.Ended -> removeFromIncomingQueue(ev.channelId)
        }
    }

    private suspend fun handleCallStarted(ev: MainGatewayBridge.CallEvent.Started) {
        val selfId = selfUserIdProvider() ?: return
        // Self-ring filter — outgoing calls we initiated also fire CALL_CREATE but without
        // self in `ringing` (Discord does not ring the initiator).
        if (selfId !in ev.ringing) return
        if (_incomingCalls.value.any { it.channelId == ev.channelId }) return
        // Caller-id chain step 2: message-author fallback when voice_states resolution failed.
        val callerId: UserId? = ev.callerId
            ?: ev.messageId?.let { msgId ->
                runCatching { mainGateway.resolveMessageAuthor(ev.channelId, msgId) }
                    .onFailure { Logger.i(TAG) { "incoming-call: resolveMessageAuthor failed: ${it.message}" } }
                    .getOrNull()
            }
        val call = IncomingCall(channelId = ev.channelId, callerId = callerId, isGroup = false)
        _incomingCalls.update { it + call }
    }

    private fun removeFromIncomingQueue(channelId: ChannelId) {
        _incomingCalls.update { list -> list.filterNot { it.channelId == channelId } }
    }

    override suspend fun acceptIncoming(channelId: ChannelId) {
        if (_incomingCalls.value.none { it.channelId == channelId }) return
        removeFromIncomingQueue(channelId)
        // Reuse outgoing-call path from issue #16 Slice 1; guildId=null for DM.
        connect(guildId = null, channelId = channelId)
    }

    override suspend fun declineIncoming(channelId: ChannelId) {
        val self = selfUserIdProvider() ?: return
        if (_incomingCalls.value.none { it.channelId == channelId }) return
        removeFromIncomingQueue(channelId)
        runCatching { mainGateway.stopRinging(channelId, listOf(self)) }
            .onFailure { Logger.w(TAG) { "declineIncoming: stopRinging failed: ${it.message}" } }
    }

    private fun refreshDevices() {
        val merged = listAudioDevices(AudioDevice.Direction.Capture) +
            listAudioDevices(AudioDevice.Direction.Playback)
        _devices.value = merged
    }

    override suspend fun connect(guildId: GuildId?, channelId: ChannelId): Unit = mutex.withLock {
        if (_state.value !is VoiceState.Idle && _state.value !is VoiceState.Failed) {
            Logger.w(TAG) { "connect() rejected: state=${_state.value}" }
            throw VoiceBusyException()
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
        // Inherit the application scope's dispatcher so tests can run on a TestDispatcher and
        // production still gets the IO/Default-backed scope it was configured with.
        val sScope = CoroutineScope(applicationScope.coroutineContext + sJob)
        sessionJob = sJob
        sessionScope = sScope

        sScope.launch {
            try {
                runConnect(guildId, channelId, selfId)
            } catch (e: CancellationException) {
                // disconnect() cancelled us — let cancellation propagate, no failure state.
                throw e
            } catch (t: Throwable) {
                Logger.w(TAG) { "voice connect failed: ${t.message}" }
                fail(t.message ?: t::class.simpleName.orEmpty())
                cleanup()
            }
        }
    }

    private suspend fun runConnect(guildId: GuildId?, channelId: ChannelId, selfId: UserId) {
        // Send Op 4 — server will reply with VOICE_STATE_UPDATE + VOICE_SERVER_UPDATE. For DM
        // calls (guildId == null) VOICE_SERVER_UPDATE only fires once the recipient picks up,
        // so the timeout window is larger (Ringing state).
        mainGateway.sendVoiceStateUpdate(guildId, channelId, selfMute = false, selfDeaf = false)

        val stateUpdate = withTimeoutOrNull(STATE_UPDATE_TIMEOUT_MS) {
            mainGateway.voiceStateUpdates
                .filterIsInstance<MainGatewayBridge.VoiceStateUpdate>()
                .first { it.userId == selfId && it.guildId == guildId && it.channelId == channelId }
        } ?: error(if (guildId == null) "Couldn't reach recipient" else "timed out waiting for VOICE_STATE_UPDATE")

        if (guildId == null) {
            // DM call: recipient has been notified — visual ringback until they pick up.
            _state.value = VoiceState.Ringing(channelId)
        }

        val serverTimeout = if (guildId == null) DM_SERVER_UPDATE_TIMEOUT_MS else STATE_UPDATE_TIMEOUT_MS
        val serverUpdate = withTimeoutOrNull(serverTimeout) {
            mainGateway.voiceServerUpdates.first { it.guildId == guildId && !it.endpoint.isNullOrBlank() }
        } ?: error(if (guildId == null) "No answer" else "timed out waiting for VOICE_SERVER_UPDATE")

        val endpointHost = serverUpdate.endpoint!!.substringBefore(":")
        val wsUrl = "wss://${serverUpdate.endpoint}/?v=8"
        val scope = checkNotNull(sessionScope) { "session scope null" }

        // For DM calls Discord uses the channel_id as the voice server_id (no guild context).
        val serverId = guildId?.value?.toString() ?: channelId.value.toString()

        val gw = DefaultVoiceGatewayConnection(scope = scope, transportFactory = voiceTransportFactory.delegate)
        voiceGateway = gw
        scope.launch { collectVoiceGatewayEvents(gw, endpointHost) }
        gw.connect(
            endpoint = wsUrl,
            token = serverUpdate.token,
            sessionId = stateUpdate.sessionId,
            serverId = serverId,
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
                    activeVideoSsrc = ev.videoSsrc
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
                        if (ev.daveProtocolVersion > 0) {
                            initDaveSession(ssrc)
                        }
                        startPipelines(ssrc, ev.secretKey)
                        installScreenShareClient(ev.secretKey, ev.videoCodec)
                        emitConnected()
                    }
                }
                is VoiceGatewayEvent.Speaking -> {
                    runCatching {
                        val uid = UserId(ev.userId.toLong())
                        ssrcToUser[ev.ssrc] = uid
                        _participants.value = ssrcToUser.values.toSet()
                    }
                }
                is VoiceGatewayEvent.ClientDisconnect -> {
                    // Remove any SSRC mapped to this user (cheap linear scan; user count tiny).
                    runCatching {
                        val uid = UserId(ev.userId.toLong())
                        ssrcToUser.entries.removeAll { it.value == uid }
                        _participants.value = ssrcToUser.values.toSet()
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
        val t = JvmVoiceUdpTransportFactory.create(Endpoint(host = host, port = port), localBind = null)
        udp = t
        val result = t.discoverIp(ssrc)
        gw.sendSelectProtocol(result.address, result.port, mode = AEAD_MODE)
    }

    private fun startPipelines(ssrc: Int, secretKey: ByteArray) {
        val scope = checkNotNull(sessionScope)
        val udpT = checkNotNull(udp)
        val aead = xchacha20Poly1305(secretKey)
        val codec = VoicePacketCodec(aead = aead, ssrc = ssrc)
        packetCodec = codec

        // Single owner of UDP receive — fans out to audio and video consumers.
        val dispatcher = VoicePacketDispatcher(udpT)
        packetDispatcher = dispatcher
        dispatcher.start(scope)

        val encoder = OpusCodecFactory.createEncoder()
        val daveSess = daveSession
        val captureDaveHook: (suspend (ByteArray) -> ByteArray)? = daveSess?.let { sess ->
            { opus ->
                val enc = encryptorFor(sess, ssrc)
                enc?.encrypt(opus) ?: opus
            }
        }
        val playbackDaveHook: (suspend (ssrc: Int, ByteArray) -> ByteArray?)? = daveSess?.let { sess ->
            { remoteSsrc, ciphertext ->
                val uid = ssrcToUser[remoteSsrc]?.value?.toString()
                if (uid == null) {
                    // Sender unknown yet — pass through (will be re-encrypted on next Op 5).
                    ciphertext
                } else {
                    val dec = decryptorFor(sess, uid, remoteSsrc)
                    dec?.decrypt(ciphertext) ?: ciphertext
                }
            }
        }
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
            daveEncrypt = captureDaveHook,
        )
        val play = PlaybackPipeline(
            transport = udpT,
            packetCodec = codec,
            decoderFactory = { OpusCodecFactory.createDecoder() },
            playback = audioPlayback(),
            onSpeakers = { activeSsrcs ->
                updateSpeakers(activeSsrcs)
            },
            packetSource = { dispatcher.audioPackets() },
            daveDecrypt = playbackDaveHook,
        )
        capture = cap
        playback = play
        cap.start(scope)
        play.start(scope)

        // Incoming screenshare video — uses a separate VoicePacketCodec instance (same key,
        // independent nonce counter sequence) per architect report 2026-05-23-screenshare.md §5.
        // SSRC 0 is fine for *decode-only* — encode() / nonce counter is never invoked here.
        val videoCodec = VoicePacketCodec(aead = xchacha20Poly1305(secretKey), ssrc = 0)
        videoPacketCodec = videoCodec
        val videoPipeline = IncomingVideoPipeline(
            dispatcher = dispatcher,
            packetCodec = videoCodec,
            decoderFactory = LibavH264DecoderFactory,
        )
        incomingVideoPipeline = videoPipeline
        videoPipeline.start(scope)
        videoFramesCollectorJob = scope.launch {
            videoPipeline.frames.collect { byCodecSsrc ->
                _incomingVideo.value = byCodecSsrc.mapValues { (_, f) ->
                    IncomingVideoFrame(rgba = f.rgba, width = f.width, height = f.height)
                }
            }
        }
    }

    private fun initDaveSession(ssrc: Int) {
        val scope = sessionScope ?: return
        val gw = voiceGateway ?: return
        val channelId = activeChannelId?.value?.toString() ?: return
        val userId = selfUserIdProvider()?.value?.toString() ?: return
        val session = DaveSession(
            mlsClient = mlsClient(),
            channelId = channelId,
            userId = userId,
            sendBinary = { op, payload ->
                val seq = daveSeq.getAndIncrement().toUShort()
                gw.sendBinary(DaveBinaryFrame.write(seq, op, payload))
            },
            sendJson = { op, json ->
                gw.sendDaveJson(op, json)
            },
        )
        daveSession = session
        gw.setDaveBinaryHandler { op, payload -> session.handleBinaryOp(op, payload) }
        gw.setDaveJsonHandler { op, body -> session.handleJsonOp(op, body) }
        // Init + collect state updates into the UI flow.
        scope.launch { runCatching { session.init() } }
        daveStateCollectorJob = scope.launch {
            session.state.collect { st ->
                _daveState.value = when (st) {
                    DaveSession.State.Idle,
                    DaveSession.State.Initialized,
                    DaveSession.State.Reinitializing,
                    is DaveSession.State.PreparingTransition,
                    -> DaveUiState.Connecting
                    is DaveSession.State.Active -> DaveUiState.Active(st.currentEpoch)
                    is DaveSession.State.Disabled -> DaveUiState.Disabled(st.reason)
                }
            }
        }
        Logger.i(TAG) { "DAVE session initialized for ssrc=$ssrc channel=$channelId" }
    }

    private suspend fun encryptorFor(sess: DaveSession, ssrc: Int): FrameEncryptor? {
        daveEncryptors[ssrc]?.let { return it }
        val fresh = sess.frameEncryptor(ssrc) ?: return null
        daveEncryptors[ssrc] = fresh
        return fresh
    }

    private suspend fun decryptorFor(sess: DaveSession, userId: String, ssrc: Int): FrameDecryptor? {
        val key = (ssrc.toLong() and 0xFFFFFFFFL) or (userId.hashCode().toLong() shl 32)
        daveDecryptors[key]?.let { return it }
        val fresh = sess.frameDecryptor(userId, ssrc) ?: return null
        daveDecryptors[key] = fresh
        return fresh
    }

    private fun installScreenShareClient(secretKey: ByteArray, negotiatedCodecName: String?) {
        val scope = sessionScope ?: return
        val udpT = udp ?: return
        val gw = voiceGateway ?: return
        // Per architect report 2026-05-23-screenshare.md §4: SessionDescription.video_codec is
        // the codec Discord picked from our SelectProtocol advertisement. When the field is
        // missing (older test fixtures, certain server builds) fall back to the highest-priority
        // codec we advertised (H.264) — chooseCodec(emptyList) would return null which would
        // crash the screenshare client install at session-up time.
        val codec = negotiatedCodecName
            ?.let { chooseCodec(listOf(it)) }
            ?: VideoCodec.H264
        screenShareImpl = DefaultScreenShareClient(
            voiceGateway = gw,
            packetEncryptor = xchacha20Poly1305(secretKey),
            nonceGen = NonceGenerator(VIDEO_NONCE_INITIAL),
            udpTransport = udpT,
            getAudioSsrc = { activeSsrc },
            getVideoSsrc = { activeVideoSsrc },
            enumerator = screenSourceEnumerator(),
            scope = scope,
            videoCodec = codec,
        )
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
        val channelId = activeChannelId
        val midHandshake = _state.value is VoiceState.Connecting || _state.value is VoiceState.Ringing
        // Cancel mid-handshake: send revocation Op 4 (channel_id=null) BEFORE local cleanup so
        // Discord drops the pending call rather than orphaning server-side state.
        if (midHandshake && channelId != null) {
            runCatching {
                mainGateway.sendVoiceStateUpdate(
                    guildId = guildId,
                    channelId = null,
                    selfMute = false,
                    selfDeaf = false,
                )
            }
        }
        runCatching { screenShareImpl.stop() }
        screenShareImpl = NoOpScreenShareClient()
        runCatching { capture?.stop() }
        runCatching { playback?.stop() }
        runCatching { incomingVideoPipeline?.stop() } // suspend

        runCatching { packetDispatcher?.stop() }
        runCatching { videoFramesCollectorJob?.cancel() }
        runCatching { daveStateCollectorJob?.cancel() }
        runCatching { daveEncryptors.values.forEach { it.close() } }
        runCatching { daveDecryptors.values.forEach { it.close() } }
        daveEncryptors.clear()
        daveDecryptors.clear()
        runCatching { daveSession?.close() }
        daveSession = null
        _daveState.value = DaveUiState.Off
        runCatching { voiceGateway?.close() }
        runCatching { udp?.close() }
        // For fully-Connected sessions (mid-handshake already revoked above via the
        // pre-cleanup Op 4) emit the normal leave Op 4. The `!midHandshake` guard ensures
        // exactly one Op 4 per disconnect — no double-send.
        if (!midHandshake) {
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
        incomingVideoPipeline = null
        packetDispatcher = null
        videoFramesCollectorJob = null
        voiceGateway = null
        udp = null
        packetCodec = null
        videoPacketCodec = null
        _incomingVideo.value = emptyMap()
        activeGuildId = null
        activeChannelId = null
        activeVideoSsrc = 0
        ssrcToUser.clear()
        _participants.value = emptySet()
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
            // Pause/resume the encode-send path WITHOUT tearing down the encoder or the
            // capture device. The loop keeps draining the line while muted, so toggling
            // mute is now cycle-safe (mute → unmute → mute → unmute …).
            capture?.setMuted(muted)
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
            // Capture pause is reversible; playback stop is acceptable because
            // un-deafening currently requires a reconnect cycle (deferred; see report §10).
            capture?.setMuted(mute)
            if (deaf) {
                playback?.stop()
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

    override suspend fun pairwiseFingerprint(userId: UserId): String? {
        val sess = daveSession ?: return null
        val bytes = sess.pairwiseFingerprint(userId.value.toString()) ?: return null
        if (bytes.isEmpty()) return null
        return dev.puklic.voice.dave.sas.SasFormatter.format(bytes)
    }

    private fun fail(reason: String) {
        // Belt-and-suspenders: CancellationException now propagates from runConnect (see
        // connect()), so a cancelled coroutine should never reach here. This Idle guard
        // remains as defense-in-depth against any future code path that might call fail()
        // from a post-disconnect context.
        if (_state.value is VoiceState.Idle) return
        _state.value = VoiceState.Failed(reason = reason, recoverable = true)
    }

    private companion object {
        const val TAG = "DefaultVoiceClient"
        const val STATE_UPDATE_TIMEOUT_MS = 10_000L
        const val DM_SERVER_UPDATE_TIMEOUT_MS = 30_000L
        const val AEAD_MODE = "aead_xchacha20_poly1305_rtpsize"

        // Video uses a separate AEAD cipher instance (a fresh [NonceGenerator]). Both audio
        // and video share the same 32 B SessionDescription key but increment independent
        // counters because they target separate SSRCs (architect report screenshare §5).
        const val VIDEO_NONCE_INITIAL = 0
    }
}
