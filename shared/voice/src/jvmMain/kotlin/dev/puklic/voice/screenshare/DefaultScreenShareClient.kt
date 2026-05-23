package dev.puklic.voice.screenshare

import co.touchlab.kermit.Logger
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.screenshare.encoder.VideoEncoder
import dev.puklic.voice.screenshare.encoder.ffmpegVideoEncoder
import dev.puklic.voice.screenshare.encoder.libavVideoEncoder
import dev.puklic.voice.screenshare.source.ScreenSourceEnumerator
import dev.puklic.voice.transport.RtpPacket
import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.VideoRtpSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Default JVM [ScreenShareClient]. Orchestrates ffmpeg-based video encoding and pushes encoded
 * H.264 frames through [VideoRtpSender]. See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §2 and §10 slice 5.
 *
 * Constructed by [dev.puklic.voice.DefaultVoiceClient] once the parent voice session reaches
 * `Connected` (SessionDescription received). Until then a [NoOpScreenShareClient] is exposed.
 */
@Suppress("LongParameterList")
internal class DefaultScreenShareClient(
    private val voiceGateway: VoiceGatewayConnection,
    private val packetEncryptor: AeadCipher,
    private val nonceGen: NonceGenerator,
    private val udpTransport: UdpRtpTransport,
    private val getAudioSsrc: () -> Int,
    private val getVideoSsrc: () -> Int,
    private val enumerator: ScreenSourceEnumerator,
    private val scope: CoroutineScope,
    private val encoderFactory: (ScreenSource, Boolean) -> VideoEncoder = { src, audio ->
        // Self-contained Phase 2: default to in-process libavcodec encoder. Set
        // `-Dpuklic.voice.encoder=cli` to force the legacy subprocess path (dev/debug only).
        if (System.getProperty(ENCODER_PROPERTY, ENCODER_LIBAV) == ENCODER_CLI) {
            ffmpegVideoEncoder(src, audio)
        } else {
            libavVideoEncoder(src, audio)
        }
    },
    private val sendDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScreenShareClient {

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    override val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var encoder: VideoEncoder? = null
    private var sendJob: Job? = null

    override suspend fun listSources(): List<ScreenSource> = enumerator.list()

    override suspend fun start(source: ScreenSource, shareAudio: Boolean): Unit = mutex.withLock {
        check(_state.value is ScreenShareState.Idle || _state.value is ScreenShareState.Failed) {
            "Screen share already in progress: ${_state.value}"
        }
        _state.value = ScreenShareState.Negotiating(source)

        val audioSsrc = getAudioSsrc()
        val videoSsrc = getVideoSsrc()
        if (videoSsrc == 0) {
            _state.value = ScreenShareState.Failed("Voice server did not assign video_ssrc")
            return
        }

        // Op 12 — announce active video stream.
        voiceGateway.sendVideoStream(
            audioSsrc = audioSsrc,
            videoSsrc = videoSsrc,
            rtxSsrc = 0,
            active = true,
        )

        val enc = encoderFactory(source, shareAudio)
        encoder = enc
        val sender = VideoRtpSender(
            udp = udpTransport,
            encryptor = packetEncryptor,
            nonceGen = nonceGen,
            videoSsrc = videoSsrc,
            payloadType = RtpPacket.PAYLOAD_TYPE_H264,
        )

        // Op 5 — speaking bitmask MICROPHONE(1) | SOUNDSHARE(2) = 3.
        if (shareAudio) {
            voiceGateway.sendSpeaking(SPEAKING_MIC_AND_SOUNDSHARE, audioSsrc)
        }

        sendJob = scope.launch(sendDispatcher) {
            try {
                enc.encode().collect { frame ->
                    sender.send(frame, frame.ts90k)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(TAG) { "encoder error: ${e.message}" }
                _state.value = ScreenShareState.Failed("Encoder error: ${e.message}")
            }
        }

        _state.value = ScreenShareState.Active(
            source = source,
            videoSsrc = videoSsrc,
            withAudio = shareAudio,
        )
    }

    override suspend fun stop(): Unit = mutex.withLock {
        sendJob?.cancel()
        sendJob = null
        runCatching { encoder?.stop() }
        encoder = null

        runCatching {
            voiceGateway.sendVideoStream(
                audioSsrc = getAudioSsrc(),
                videoSsrc = getVideoSsrc(),
                rtxSsrc = 0,
                active = false,
            )
        }
        runCatching {
            voiceGateway.sendSpeaking(SPEAKING_MIC_ONLY, getAudioSsrc())
        }
        _state.value = ScreenShareState.Idle
    }

    private companion object {
        const val TAG = "DefaultScreenShareClient"
        const val SPEAKING_MIC_ONLY = 1
        const val SPEAKING_MIC_AND_SOUNDSHARE = 3
        const val ENCODER_PROPERTY = "puklic.voice.encoder"
        const val ENCODER_LIBAV = "libav"
        const val ENCODER_CLI = "cli"
    }
}
