@file:OptIn(dev.puklic.voice.codec.PuklicVoiceCodec::class)

package dev.puklic.voice.screenshare

import dev.puklic.screencast.ScreenSourceEnumerator
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.gateway.VoiceGatewayConnection
import dev.puklic.voice.gateway.VoiceGatewayEvent
import dev.puklic.voice.gateway.VoiceGatewayState
import dev.puklic.voice.screenshare.encoder.VideoCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Contract test for the screen-share SPI declared in `:shared:voice-codec/commonMain`.
 * Pins the factory shape that JVM `JvmScreenShareClientFactory` satisfies and that the
 * upcoming FP-14h-7 promotion of `DefaultVoiceClient` will consume.
 */
class ScreenShareClientFactoryContractTest {

    @Test
    fun factory_returns_screen_share_client_with_arguments_propagated() = runTest {
        val capture = CapturingFactory()
        val client = capture.create(
            voiceGateway = NoopVoiceGateway(),
            packetEncryptor = NoopAeadCipher,
            nonceGen = NonceGenerator(0),
            udpTransport = NoopUdp,
            getAudioSsrc = { 1001 },
            getVideoSsrc = { 1002 },
            enumerator = NoopEnumerator,
            scope = CoroutineScope(Dispatchers.Unconfined),
            videoCodec = VideoCodec.VP8,
        )
        assertSame(StubScreenShareClient, client)
        assertEquals(1001, capture.recordedAudioSsrc)
        assertEquals(1002, capture.recordedVideoSsrc)
        assertEquals(VideoCodec.VP8, capture.recordedCodec)
    }
}

private class CapturingFactory : ScreenShareClientFactory {
    var recordedAudioSsrc: Int = -1
    var recordedVideoSsrc: Int = -1
    var recordedCodec: VideoCodec? = null

    override fun create(
        voiceGateway: VoiceGatewayConnection,
        packetEncryptor: AeadCipher,
        nonceGen: NonceGenerator,
        udpTransport: VoiceUdpTransport,
        getAudioSsrc: () -> Int,
        getVideoSsrc: () -> Int,
        enumerator: ScreenSourceEnumerator,
        scope: CoroutineScope,
        videoCodec: VideoCodec,
    ): ScreenShareClient {
        recordedAudioSsrc = getAudioSsrc()
        recordedVideoSsrc = getVideoSsrc()
        recordedCodec = videoCodec
        return StubScreenShareClient
    }
}

private object StubScreenShareClient : ScreenShareClient {
    override val state = MutableStateFlow<ScreenShareState>(ScreenShareState.Idle).asStateFlow()
    override suspend fun listSources(): List<ScreenSource> = emptyList()
    override suspend fun start(source: ScreenSource, shareAudio: Boolean) {}
    override suspend fun stop() {}
}

private object NoopAeadCipher : AeadCipher {
    override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray = plaintext
    override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray = ciphertextWithTag
}

private object NoopUdp : VoiceUdpTransport {
    override suspend fun send(packet: ByteArray) {}
    override val incoming: Flow<ByteArray> = emptyFlow()
    override fun close() {}
}

private object NoopEnumerator : ScreenSourceEnumerator {
    override suspend fun list(): List<ScreenSource> = emptyList()
}

private class NoopVoiceGateway : VoiceGatewayConnection {
    private val stateFlow = MutableStateFlow<VoiceGatewayState>(VoiceGatewayState.Idle)
    private val eventsFlow = MutableSharedFlow<VoiceGatewayEvent>()
    override val state: StateFlow<VoiceGatewayState> = stateFlow.asStateFlow()
    override val events: SharedFlow<VoiceGatewayEvent> = eventsFlow.asSharedFlow()
    override suspend fun connect(endpoint: String, token: String, sessionId: String, serverId: String, userId: String) {}
    override suspend fun sendSelectProtocol(externalIp: String, externalPort: Int, mode: String) {}
    override suspend fun sendSpeaking(speaking: Int, ssrc: Int) {}
    override suspend fun sendVideoStream(audioSsrc: Int, videoSsrc: Int, rtxSsrc: Int, active: Boolean) {}
    override suspend fun sendBinary(bytes: ByteArray) {}
    override suspend fun sendDaveJson(op: Int, jsonBody: String) {}
    override fun setDaveBinaryHandler(handler: suspend (op: Int, payload: ByteArray) -> Unit) {}
    override fun setDaveJsonHandler(handler: suspend (op: Int, body: kotlinx.serialization.json.JsonElement) -> Unit) {}
    override suspend fun close() {}
}
