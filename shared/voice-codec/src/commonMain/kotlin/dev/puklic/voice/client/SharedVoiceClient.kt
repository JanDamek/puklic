package dev.puklic.voice.client

import co.touchlab.kermit.Logger
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.voice.AudioDevice
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.IncomingCall
import dev.puklic.voice.IncomingVideoFrame
import dev.puklic.voice.VoiceBusyException
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.VoiceState
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.Endpoint
import dev.puklic.voice.gateway.DefaultVoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayEvent
import dev.puklic.voice.gateway.VoiceGatewayState
import dev.puklic.voice.screenshare.NoOpScreenShareClient
import dev.puklic.voice.screenshare.ScreenShareClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The DAVE-free voice engine shared by every platform that has no end-to-end encryption backend
 * (Android today, the Apple App Store builds later). It runs the whole call: Op 4 on the main
 * gateway, the voice gateway handshake, UDP discovery, protocol selection and the capture /
 * playback pipelines with per-hop `aead_xchacha20_poly1305_rtpsize` encryption.
 *
 * It announces DAVE protocol version 0, so Discord never negotiates end-to-end encryption with it;
 * a server that insists on DAVE anyway ends the call with a failure instead of silent audio.
 * Screen sharing and incoming video are not supported and stay at their idle values.
 */
@PuklicVoiceCodec
public class SharedVoiceClient(
    private val applicationScope: CoroutineScope,
    private val signalling: VoiceSignalling,
    private val platform: VoicePlatform,
) : VoiceClient {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<AudioDevice>>(emptyList())
    override val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    override val screenShare: ScreenShareClient = NoOpScreenShareClient()
    override val daveState: StateFlow<DaveUiState> = MutableStateFlow<DaveUiState>(DaveUiState.Off).asStateFlow()
    override val incomingVideo: StateFlow<Map<Int, IncomingVideoFrame>> =
        MutableStateFlow<Map<Int, IncomingVideoFrame>>(emptyMap()).asStateFlow()

    private val incomingQueue = IncomingCallQueue(applicationScope, signalling)
    override val incomingCalls: StateFlow<List<IncomingCall>> = incomingQueue.calls

    private val participantTracker = ParticipantTracker()
    override val participants: StateFlow<Set<UserId>> = participantTracker.participants

    private val handshake = VoiceHandshake(signalling.mainGateway)
    private val connectMutex = Mutex()
    private val activeSession = MutableStateFlow<ActiveSession?>(null)
    private var deviceSelection = DeviceSelection(captureId = null, playbackId = null)
    private var mutedBeforeDeafen = false

    init {
        refreshDevices()
    }

    override suspend fun acceptIncoming(channelId: ChannelId) {
        if (!incomingQueue.contains(channelId)) return
        incomingQueue.remove(channelId)
        connect(guildId = null, channelId = channelId)
    }

    override suspend fun declineIncoming(channelId: ChannelId) {
        val selfId = signalling.selfUserIdProvider() ?: return
        if (!incomingQueue.contains(channelId)) return
        incomingQueue.remove(channelId)
        runCatching { signalling.mainGateway.stopRinging(channelId, listOf(selfId)) }
            .onFailure { Logger.w(TAG) { "declineIncoming: stopRinging failed: ${it.message}" } }
    }

    override suspend fun connect(guildId: GuildId?, channelId: ChannelId): Unit = connectMutex.withLock {
        if (_state.value !is VoiceState.Idle && _state.value !is VoiceState.Failed) throw VoiceBusyException()
        refreshDevices()
        _state.value = VoiceState.Connecting(channelId, attempt = 1)
        val selfId = signalling.selfUserIdProvider() ?: return fail("self user id not available")
        val job = SupervisorJob(applicationScope.coroutineContext[Job])
        val session = ActiveSession(guildId, channelId, CoroutineScope(applicationScope.coroutineContext + job))
        activeSession.value = session
        session.scope.launch { runGuarded(session) { join(session, selfId) } }
    }

    private suspend fun runGuarded(session: ActiveSession, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Logger.w(TAG) { "voice session failed: ${failure.message}" }
            abort(session, failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    private suspend fun join(session: ActiveSession, selfId: UserId) {
        val request = JoinRequest(session.guildId, session.channelId, selfId)
        val grant = handshake.obtainGrant(request) { _state.value = VoiceState.Ringing(session.channelId) }
        val gateway = DefaultVoiceGatewayConnection(
            scope = session.scope,
            transportFactory = platform.wsTransportFactory.delegate,
            maxDaveProtocolVersion = NO_DAVE,
        )
        session.gateway = gateway
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.events.collect { event -> runGuarded(session) { handle(session, event) } }
        }
        session.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.state.collect { if (it is VoiceGatewayState.Failed) abort(session, it.reason) }
        }
        gateway.connect(
            endpoint = "wss://${grant.endpoint}/?v=$VOICE_GATEWAY_VERSION",
            token = grant.token,
            sessionId = grant.sessionId,
            serverId = (session.guildId?.value ?: session.channelId.value).toString(),
            userId = selfId.value.toString(),
        )
    }

    private suspend fun handle(session: ActiveSession, event: VoiceGatewayEvent) {
        when (event) {
            is VoiceGatewayEvent.Ready -> onReady(session, event)
            is VoiceGatewayEvent.SessionDescription -> onSessionDescription(session, event)
            is VoiceGatewayEvent.Speaking -> participantTracker.onSpeaking(event.userId, event.ssrc)
            is VoiceGatewayEvent.ClientDisconnect -> participantTracker.onDisconnect(event.userId)
        }
    }

    private suspend fun onReady(session: ActiveSession, ready: VoiceGatewayEvent.Ready) {
        val gateway = checkNotNull(session.gateway)
        session.media?.close()
        val media = newMediaSession(session, gateway, ready.ssrc)
        session.media = media
        session.ssrc = ready.ssrc
        val external = media.discover(Endpoint(host = ready.ip, port = ready.port), ready.ssrc)
        gateway.sendSelectProtocol(external.address, external.port, mode = AEAD_MODE)
    }

    private fun newMediaSession(
        session: ActiveSession,
        gateway: VoiceGatewayConnection,
        ssrc: Int,
    ) = VoiceMediaSession(
        platform = platform,
        scope = session.scope,
        callbacks = MediaCallbacks(
            onSpeakingChange = { speaking -> gateway.sendSpeaking(if (speaking) SPEAKING_MICROPHONE else 0, ssrc) },
            onSpeakers = { activeSsrcs -> publishSpeakers(participantTracker.usersFor(activeSsrcs)) },
        ),
    )

    private suspend fun onSessionDescription(
        session: ActiveSession,
        description: VoiceGatewayEvent.SessionDescription,
    ) {
        if (description.daveProtocolVersion > 0) return abort(session, DAVE_REQUIRED_MESSAGE)
        val media = session.media ?: return abort(session, "session description before UDP discovery")
        media.start(session.ssrc, description.secretKey, deviceSelection)
        _state.value = VoiceState.Connected(
            channelId = session.channelId,
            ssrc = session.ssrc,
            selfMute = false,
            selfDeaf = false,
            speakers = emptySet(),
        )
    }

    private fun publishSpeakers(users: Set<UserId>) = _state.update { current ->
        if (current is VoiceState.Connected && current.speakers != users) current.copy(speakers = users) else current
    }

    override suspend fun disconnect(): Unit = connectMutex.withLock {
        activeSession.value?.let { release(it) }
        _state.value = VoiceState.Idle
    }

    private suspend fun abort(session: ActiveSession, reason: String) {
        if (activeSession.value !== session) return
        release(session)
        _state.value = VoiceState.Failed(reason = reason, recoverable = true)
    }

    private suspend fun release(session: ActiveSession) {
        if (!activeSession.compareAndSet(session, null)) return
        session.media?.close()
        runCatching { session.gateway?.close() }
        runCatching {
            signalling.mainGateway.sendVoiceStateUpdate(session.guildId, null, selfMute = false, selfDeaf = false)
        }
        participantTracker.clear()
        session.scope.cancel()
    }

    override fun setSelfMute(muted: Boolean) = updateAudioState { current ->
        current.copy(selfMute = muted, selfDeaf = current.selfDeaf && muted)
    }

    override fun setSelfDeaf(deaf: Boolean) = updateAudioState { current ->
        if (deaf) mutedBeforeDeafen = current.selfMute
        current.copy(selfDeaf = deaf, selfMute = deaf || mutedBeforeDeafen)
    }

    private fun updateAudioState(transform: (VoiceState.Connected) -> VoiceState.Connected) {
        val session = activeSession.value ?: return
        val current = _state.value as? VoiceState.Connected ?: return
        val next = transform(current)
        _state.value = next
        session.media?.applyAudioState(muted = next.selfMute, deafened = next.selfDeaf)
        applicationScope.launch {
            runCatching {
                signalling.mainGateway.sendVoiceStateUpdate(
                    guildId = session.guildId,
                    channelId = session.channelId,
                    selfMute = next.selfMute,
                    selfDeaf = next.selfDeaf,
                )
            }
        }
    }

    override fun selectCaptureDevice(id: String) {
        deviceSelection = deviceSelection.copy(captureId = id)
        refreshDevices()
    }

    override fun selectPlaybackDevice(id: String) {
        deviceSelection = deviceSelection.copy(playbackId = id)
        refreshDevices()
    }

    override suspend fun pairwiseFingerprint(userId: UserId): String? = null

    private fun refreshDevices() {
        val lister = platform.audio.deviceLister
        _devices.value = lister(AudioDevice.Direction.Capture) + lister(AudioDevice.Direction.Playback)
    }

    private fun fail(reason: String) {
        _state.value = VoiceState.Failed(reason = reason, recoverable = true)
    }

    private companion object {
        const val TAG = "SharedVoiceClient"
        const val AEAD_MODE = "aead_xchacha20_poly1305_rtpsize"
        const val VOICE_GATEWAY_VERSION = 8
        const val NO_DAVE = 0
        const val SPEAKING_MICROPHONE = 1
        const val DAVE_REQUIRED_MESSAGE =
            "The voice server requires end-to-end encryption (DAVE), which this client does not support"
    }
}
