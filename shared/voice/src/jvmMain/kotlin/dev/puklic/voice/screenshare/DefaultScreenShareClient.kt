package dev.puklic.voice.screenshare

import co.touchlab.kermit.Logger
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.screenshare.encoder.VideoCodec
import dev.puklic.voice.screenshare.encoder.VideoEncoder
import dev.puklic.voice.screenshare.encoder.ffmpegVideoEncoder
import dev.puklic.voice.screenshare.encoder.libavVideoEncoder
import dev.puklic.voice.screenshare.linux.LinuxPortalScreenCast
import dev.puklic.voice.screenshare.source.LinuxScreenSourceEnumerator
import dev.puklic.voice.screenshare.source.ScreenSourceEnumerator
import dev.puklic.voice.transport.H264FrameFragmenter
import dev.puklic.voice.transport.UdpRtpTransport
import dev.puklic.voice.transport.VideoFrameFragmenter
import dev.puklic.voice.transport.VideoRtpSender
import dev.puklic.voice.transport.Vp8Packetiser
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
    /**
     * Codec selected by Discord's SessionDescription (`video_codec` field) and resolved via
     * [dev.puklic.voice.screenshare.encoder.chooseCodec]. Drives BOTH the encoder backend
     * (libx264 vs libvpx) and the RTP send pipeline (payload type + packetisation strategy).
     */
    private val videoCodec: VideoCodec = VideoCodec.H264,
    private val encoderFactory: (ScreenSource, Boolean, VideoCodec) -> VideoEncoder = { src, audio, codec ->
        // Self-contained Phase 2: default to in-process libavcodec encoder. Set
        // `-Dpuklic.voice.encoder=cli` to force the legacy subprocess path (dev/debug only).
        if (System.getProperty(ENCODER_PROPERTY, ENCODER_LIBAV) == ENCODER_CLI) {
            ffmpegVideoEncoder(src, audio)
        } else {
            libavVideoEncoder(src, audio, codec)
        }
    },
    /**
     * Test seam. Defaults to constructing a fresh [LinuxPortalScreenCast] on demand. Production
     * Linux path: invoked when `source.id == LinuxScreenSourceEnumerator.PORTAL_PICKER_ID`.
     */
    private val portalScreenCastFactory: () -> LinuxPortalScreenCast = { LinuxPortalScreenCast() },
    private val sendDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Test seam. Defaults to `os.name` system property; tests on a macOS dev host can pass
     * "Linux" to exercise the audio-share path (which is suppressed on macOS per the
     * 2026-05-28 scope decision — see [start]).
     */
    private val osName: String = System.getProperty("os.name").orEmpty(),
) : ScreenShareClient {

    private val _state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle)
    override val state: StateFlow<ScreenShareState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var encoder: VideoEncoder? = null
    private var sendJob: Job? = null
    private var activePortal: LinuxPortalScreenCast? = null

    override suspend fun listSources(): List<ScreenSource> = enumerator.list()

    override suspend fun start(source: ScreenSource, shareAudio: Boolean): Unit = mutex.withLock {
        check(_state.value is ScreenShareState.Idle || _state.value is ScreenShareState.Failed) {
            "Screen share already in progress: ${_state.value}"
        }
        // macOS audio share is out of scope (2026-05-28 scope decision, issue #25). The UI
        // disables the toggle on macOS, but defend the contract here too: drop a shareAudio=true
        // request on macOS with a single warning instead of attempting a capture path that
        // cannot succeed.
        @Suppress("NAME_SHADOWING")
        val shareAudio = if (shareAudio && osName.startsWith("Mac")) {
            Logger.w(TAG) { "Ignoring shareAudio=true: system audio sharing is not supported on macOS." }
            false
        } else {
            shareAudio
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

        // Linux Wayland path: when the user picked the synthetic "portal" entry from
        // [LinuxScreenSourceEnumerator], run the xdg-desktop-portal handshake to obtain a
        // PipeWire node id + fd. The compositor pops up its own picker during Start().
        val enc: VideoEncoder = if (source.id == LinuxScreenSourceEnumerator.PORTAL_PICKER_ID) {
            val portal = portalScreenCastFactory()
            activePortal = portal
            val stream = when (
                val r = portal.open(
                    captureMode = LinuxPortalScreenCast.CaptureMode.MonitorsAndWindows,
                    cursorMode = LinuxPortalScreenCast.CursorMode.Hidden,
                    includeAudio = shareAudio,
                )
            ) {
                is LinuxPortalScreenCast.PortalResult.Ok -> r.stream
                LinuxPortalScreenCast.PortalResult.UserCancelled -> {
                    runCatching { portal.close() }
                    activePortal = null
                    _state.value = ScreenShareState.Idle
                    return
                }
                is LinuxPortalScreenCast.PortalResult.Error -> {
                    runCatching { portal.close() }
                    activePortal = null
                    _state.value = ScreenShareState.Failed("xdg-desktop-portal handshake failed: ${r.message}")
                    return
                }
            }
            // Construct the encoder with the real PipeWire node id (replacing the synthetic
            // "portal" sentinel) plus the portal-allocated fd.
            // Width/height stay 0 (UNKNOWN); the encoder reads real dimensions from the
            // PipeWire stream once libavdevice opens it.
            val realSource = ScreenSource.Monitor(
                id = stream.firstVideoNodeId.toString(),
                displayName = source.displayName,
                widthPx = 0,
                heightPx = 0,
            )
            libavVideoEncoder(realSource, shareAudio, stream.fd, videoCodec)
        } else {
            encoderFactory(source, shareAudio, videoCodec)
        }
        encoder = enc
        val fragmenter: VideoFrameFragmenter = when (videoCodec) {
            VideoCodec.H264 -> H264FrameFragmenter
            VideoCodec.VP8 -> Vp8Packetiser
        }
        val sender = VideoRtpSender(
            udp = udpTransport,
            encryptor = packetEncryptor,
            nonceGen = nonceGen,
            videoSsrc = videoSsrc,
            payloadType = videoCodec.payloadType(),
            fragmenter = fragmenter,
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
        runCatching { activePortal?.close() }
        activePortal = null

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
