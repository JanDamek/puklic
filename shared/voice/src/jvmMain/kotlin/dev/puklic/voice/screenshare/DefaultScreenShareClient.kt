package dev.puklic.voice.screenshare

import co.touchlab.kermit.Logger
import dev.puklic.voice.AudioConstants
import dev.puklic.voice.codec.OpusApplication
import dev.puklic.voice.codec.OpusCodecFactory
import dev.puklic.voice.codec.OpusEncoder
import dev.puklic.voice.codec.OpusEncoderConfig
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.screenshare.encoder.VideoCodec
import dev.puklic.voice.screenshare.encoder.VideoEncoder
import dev.puklic.voice.screenshare.encoder.ffmpegVideoEncoder
import dev.puklic.voice.screenshare.encoder.libavVideoEncoder
import dev.puklic.voice.screenshare.linux.LinuxPortalScreenCast
import dev.puklic.voice.screenshare.linux.PipeWireAudioReader
import dev.puklic.voice.screenshare.source.LinuxScreenSourceEnumerator
import dev.puklic.voice.screenshare.source.ScreenSourceEnumerator
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.transport.H264FrameFragmenter
import dev.puklic.voice.transport.SoundshareAudioRtpSender
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
    private val udpTransport: VoiceUdpTransport,
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
     * Test seam: factory for the PipeWire PCM audio reader (Linux portal screencast audio).
     * Default reads from the portal-allocated PipeWire node id + fd using libavdevice.
     */
    private val audioReaderFactory: (Int, Int) -> PipeWireAudioReader = { nodeId, fd ->
        PipeWireAudioReader(nodeId = nodeId, fd = fd)
    },
    /**
     * Test seam: factory for the soundshare Opus encoder (stereo, application=Audio for
     * music-quality screencast audio per architect report §5).
     */
    private val opusEncoderFactory: () -> OpusEncoder = {
        OpusCodecFactory.createEncoder(
            OpusEncoderConfig(
                channels = AudioConstants.CHANNELS_STEREO,
                application = OpusApplication.Audio,
            ),
        )
    },
    /**
     * Test seam: factory for the soundshare RTP sender bound to the soundshare SSRC.
     */
    private val soundshareSenderFactory: (Int) -> SoundshareAudioRtpSender = { ssrc ->
        SoundshareAudioRtpSender(udp = udpTransport, aead = packetEncryptor, ssrc = ssrc)
    },
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
    private var audioSendJob: Job? = null
    private var audioReader: PipeWireAudioReader? = null
    private var audioEncoder: OpusEncoder? = null
    private var activePortal: LinuxPortalScreenCast? = null
    // Tracked across start()/stop() so stop() can release the soundshare SPEAKING flag on the
    // same SSRC that start() raised it on. Null means no share-with-audio session is active.
    private var activeSoundshareSsrc: Int? = null

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

        val micSsrc = getAudioSsrc()
        val videoSsrc = getVideoSsrc()
        if (videoSsrc == 0) {
            _state.value = ScreenShareState.Failed("Voice server did not assign video_ssrc")
            return
        }

        // Soundshare SSRC = videoSsrc + SOUNDSHARE_SSRC_OFFSET, per Discord client convention
        // verified against discord.js-selfbot-v13 and discord-rs. See architect report
        // 2026-05-28-screencast-audio-ssrc.md §4.1.
        val soundshareSsrc = videoSsrc + SOUNDSHARE_SSRC_OFFSET
        // Op 12 — announce active video stream. `audio_ssrc` binds the audio track that belongs
        // to this video: soundshare SSRC when sharing audio, mic SSRC otherwise (§4.3).
        val op12AudioSsrc = if (shareAudio) soundshareSsrc else micSsrc
        voiceGateway.sendVideoStream(
            audioSsrc = op12AudioSsrc,
            videoSsrc = videoSsrc,
            rtxSsrc = 0,
            active = true,
        )

        // Linux Wayland path: when the user picked the synthetic "portal" entry from
        // [LinuxScreenSourceEnumerator], run the xdg-desktop-portal handshake to obtain a
        // PipeWire node id + fd. The compositor pops up its own picker during Start().
        var portalStream: LinuxPortalScreenCast.PipeWireStream? = null
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
            portalStream = stream
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

        // Op 5 SPEAKING(SOUNDSHARE=2) on the soundshare SSRC. Mic SPEAKING is owned by the
        // audio-capture pipeline (DefaultVoiceClient) and stays at its own (MICROPHONE=1 / 0)
        // value — sending mask 3 on the mic SSRC would be protocol-incorrect (§4.2).
        if (shareAudio) {
            voiceGateway.sendSpeaking(SPEAKING_SOUNDSHARE, soundshareSsrc)
            activeSoundshareSsrc = soundshareSsrc
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

        // Portal screencast audio: when the compositor handed back an audio node alongside the
        // video stream, run a parallel PipeWire reader → stereo Opus encoder → soundshare RTP
        // sender. See architect report 2026-05-28-pipewire-pcm-reader.md and §5 of
        // 2026-05-28-screencast-audio-ssrc.md. Independent of mic audio (separate SSRC, separate
        // sequence/timestamp/nonce triplet).
        val capturedStream = portalStream
        val audioNodeId = capturedStream?.firstAudioNodeId
        if (shareAudio && capturedStream != null && audioNodeId != null) {
            val reader = audioReaderFactory(audioNodeId, capturedStream.fd)
            val encoder = opusEncoderFactory()
            val audioSender = soundshareSenderFactory(soundshareSsrc)
            audioReader = reader
            audioEncoder = encoder
            audioSendJob = scope.launch(sendDispatcher) {
                try {
                    reader.read().collect { pcm ->
                        audioSender.send(encoder.encode(pcm))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w(TAG) { "soundshare audio error: ${e.message}" }
                }
            }
        }

        _state.value = ScreenShareState.Active(
            source = source,
            videoSsrc = videoSsrc,
            withAudio = shareAudio,
        )
    }

    override suspend fun stop(): Unit = mutex.withLock {
        audioSendJob?.cancel()
        audioSendJob = null
        runCatching { audioReader?.stop() }
        audioReader = null
        runCatching { audioEncoder?.close() }
        audioEncoder = null
        sendJob?.cancel()
        sendJob = null
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { activePortal?.close() }
        activePortal = null

        val soundshareSsrc = activeSoundshareSsrc
        activeSoundshareSsrc = null
        runCatching {
            // Op 12 audio_ssrc on tear-down: mirror what start() bound. When share-with-audio
            // was active, the soundshare track owns the binding; otherwise the mic SSRC does.
            voiceGateway.sendVideoStream(
                audioSsrc = soundshareSsrc ?: getAudioSsrc(),
                videoSsrc = getVideoSsrc(),
                rtxSsrc = 0,
                active = false,
            )
        }
        if (soundshareSsrc != null) {
            // Release SOUNDSHARE flag on the soundshare SSRC. Mic SPEAKING is the audio
            // pipeline's responsibility — DefaultScreenShareClient never touches the mic SSRC.
            runCatching {
                voiceGateway.sendSpeaking(SPEAKING_OFF, soundshareSsrc)
            }
        }
        _state.value = ScreenShareState.Idle
    }

    private companion object {
        const val TAG = "DefaultScreenShareClient"
        // Discord SPEAKING bitmask (Op 5 `speaking` field). See architect report §4.2.
        const val SPEAKING_OFF = 0
        const val SPEAKING_SOUNDSHARE = 2
        // Soundshare SSRC = videoSsrc + SOUNDSHARE_SSRC_OFFSET, per §4.1 — Discord reserves
        // videoSsrc and the immediately-following slot in the Ready payload.
        const val SOUNDSHARE_SSRC_OFFSET = 1
        const val ENCODER_PROPERTY = "puklic.voice.encoder"
        const val ENCODER_LIBAV = "libav"
        const val ENCODER_CLI = "cli"
    }
}
