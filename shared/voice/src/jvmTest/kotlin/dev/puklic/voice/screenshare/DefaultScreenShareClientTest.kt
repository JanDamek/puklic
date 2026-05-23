package dev.puklic.voice.screenshare

import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayEvent
import dev.puklic.voice.gateway.VoiceGatewayState
import dev.puklic.voice.screenshare.encoder.VideoEncoder
import dev.puklic.voice.screenshare.source.ScreenSourceEnumerator
import dev.puklic.voice.transport.EncodedFrame
import dev.puklic.voice.transport.UdpRtpTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultScreenShareClientTest {

    @Test
    fun start_with_zero_video_ssrc_transitions_to_failed() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeVoiceGateway()
        val client = newClient(scope = this, gw = gw, videoSsrc = 0)
        val source = sampleSource()

        client.start(source, shareAudio = false)

        assertIs<ScreenShareState.Failed>(client.state.value)
        // Op 12 must NOT be sent when we never had a video ssrc.
        assertTrue(gw.videoStreams.isEmpty(), "no Op 12 expected, got: ${gw.videoStreams}")
    }

    @Test
    fun start_happy_path_sends_op12_active_and_emits_frames() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeVoiceGateway()
        val udp = CapturingTransport()
        val enc = FakeEncoder()
        val client = newClient(
            scope = this,
            gw = gw,
            udp = udp,
            videoSsrc = 0xCAFE,
            audioSsrc = 0xBEEF,
            encoderFactory = { _, _ -> enc },
        )

        client.start(sampleSource(), shareAudio = false)

        // Op 12 with active=true sent immediately.
        assertEquals(1, gw.videoStreams.size)
        val call = gw.videoStreams.single()
        assertEquals(0xBEEF, call.audioSsrc)
        assertEquals(0xCAFE, call.videoSsrc)
        assertEquals(true, call.active)

        // No Op 5 update when shareAudio = false.
        assertTrue(gw.speakings.isEmpty(), "no Op 5 expected when shareAudio=false")

        assertIs<ScreenShareState.Active>(client.state.value)
        assertEquals(0xCAFE, (client.state.value as ScreenShareState.Active).videoSsrc)

        // Emit one encoded NAL — sender should push at least one UDP packet.
        enc.emit(EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33), ts90k = 90, keyframe = true))
        // Allow the channelFlow collector to run.
        repeat(8) { yield() }

        assertTrue(udp.sent.isNotEmpty(), "expected at least one RTP packet")

        client.stop()
    }

    @Test
    fun start_with_share_audio_sends_speaking_mask_three() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeVoiceGateway()
        val client = newClient(
            scope = this,
            gw = gw,
            videoSsrc = 7,
            audioSsrc = 13,
            encoderFactory = { _, _ -> FakeEncoder() },
        )

        client.start(sampleSource(), shareAudio = true)

        // Exactly one Op 5 SPEAKING with mask 3 (MICROPHONE|SOUNDSHARE) keyed on audio SSRC.
        assertEquals(1, gw.speakings.size, "expected exactly one Op 5")
        val sp = gw.speakings.single()
        assertEquals(SPEAKING_MIC_AND_SOUNDSHARE, sp.speaking)
        assertEquals(13, sp.ssrc)

        client.stop()
    }

    @Test
    fun stop_sends_op12_inactive_and_returns_to_idle() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeVoiceGateway()
        val client = newClient(
            scope = this,
            gw = gw,
            videoSsrc = 1,
            audioSsrc = 2,
            encoderFactory = { _, _ -> FakeEncoder() },
        )
        client.start(sampleSource(), shareAudio = false)
        assertEquals(1, gw.videoStreams.size)

        client.stop()

        assertEquals(2, gw.videoStreams.size, "expected a second Op 12 from stop()")
        val stopCall = gw.videoStreams.last()
        assertEquals(false, stopCall.active)
        // Speaking reset to MIC_ONLY.
        assertTrue(
            gw.speakings.any { it.speaking == SPEAKING_MIC_ONLY && it.ssrc == 2 },
            "expected SPEAKING(1) reset on stop, got=${gw.speakings}",
        )
        assertEquals(ScreenShareState.Idle, client.state.value)
    }

    @Test
    fun calling_start_twice_without_stop_throws() = runTest(UnconfinedTestDispatcher()) {
        val gw = FakeVoiceGateway()
        val client = newClient(
            scope = this,
            gw = gw,
            videoSsrc = 1,
            audioSsrc = 2,
            encoderFactory = { _, _ -> FakeEncoder() },
        )
        client.start(sampleSource(), shareAudio = false)
        assertFails {
            client.start(sampleSource(), shareAudio = false)
        }
        client.stop()
    }

    // --- helpers ---

    private fun sampleSource(): ScreenSource =
        ScreenSource.Monitor(id = "1", displayName = "Display 1", widthPx = 1920, heightPx = 1080)

    @Suppress("LongParameterList")
    private fun newClient(
        scope: CoroutineScope,
        gw: FakeVoiceGateway,
        udp: UdpRtpTransport = CapturingTransport(),
        audioSsrc: Int = 0,
        videoSsrc: Int = 0,
        encoderFactory: (ScreenSource, Boolean) -> VideoEncoder = { _, _ -> FakeEncoder() },
    ): DefaultScreenShareClient = DefaultScreenShareClient(
        voiceGateway = gw,
        packetEncryptor = NullCipher(),
        nonceGen = NonceGenerator(0),
        udpTransport = udp,
        getAudioSsrc = { audioSsrc },
        getVideoSsrc = { videoSsrc },
        enumerator = EmptyEnumerator,
        scope = scope,
        encoderFactory = encoderFactory,
        sendDispatcher = UnconfinedTestDispatcher(),
    )

    private companion object {
        const val SPEAKING_MIC_ONLY = 1
        const val SPEAKING_MIC_AND_SOUNDSHARE = 3
    }

    private object EmptyEnumerator : ScreenSourceEnumerator {
        override suspend fun list(): List<ScreenSource> = emptyList()
    }

    private class NullCipher : AeadCipher {
        override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            plaintext + ByteArray(TAG_BYTES)
        override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            ciphertextWithTag.copyOf(ciphertextWithTag.size - TAG_BYTES)
        private companion object { const val TAG_BYTES = 16 }
    }

    private class CapturingTransport : UdpRtpTransport {
        val sent: MutableList<ByteArray> = CopyOnWriteArrayList()
        override suspend fun bind(): Int = 0
        override suspend fun connect(host: String, port: Int) {}
        override suspend fun send(packet: ByteArray) { sent += packet.copyOf() }
        override suspend fun receive(): ByteArray = ByteArray(0)
        override fun close() {}
    }

    private class FakeEncoder : VideoEncoder {
        private val channel = Channel<EncodedFrame>(Channel.UNLIMITED)
        override fun encode(): Flow<EncodedFrame> = channel.consumeAsFlow()
        override suspend fun stop() { channel.close() }
        fun emit(frame: EncodedFrame) { channel.trySend(frame) }
    }

    private data class VideoStreamCall(
        val audioSsrc: Int,
        val videoSsrc: Int,
        val rtxSsrc: Int,
        val active: Boolean,
    )
    private data class SpeakingCall(val speaking: Int, val ssrc: Int)

    private class FakeVoiceGateway : VoiceGatewayConnection {
        val videoStreams = CopyOnWriteArrayList<VideoStreamCall>()
        val speakings = CopyOnWriteArrayList<SpeakingCall>()
        private val _state = MutableStateFlow<VoiceGatewayState>(VoiceGatewayState.Idle)
        private val _events = MutableSharedFlow<VoiceGatewayEvent>(extraBufferCapacity = 8)
        override val state: StateFlow<VoiceGatewayState> = _state
        override val events: SharedFlow<VoiceGatewayEvent> = _events
        override suspend fun connect(endpoint: String, token: String, sessionId: String, serverId: String, userId: String) {}
        override suspend fun sendSelectProtocol(externalIp: String, externalPort: Int, mode: String) {}
        override suspend fun sendSpeaking(speaking: Int, ssrc: Int) {
            speakings += SpeakingCall(speaking, ssrc)
        }
        override suspend fun sendVideoStream(audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, active: Boolean) {
            videoStreams += VideoStreamCall(audioSsrc, videoSsrc, rtxSsrc, active)
        }
        override suspend fun close() {}
    }
}
