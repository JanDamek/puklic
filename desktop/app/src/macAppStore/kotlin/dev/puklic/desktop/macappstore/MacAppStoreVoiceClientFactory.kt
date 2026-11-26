package dev.puklic.desktop.macappstore

import dev.puklic.desktop.macappstore.audio.jnaAudioCapture
import dev.puklic.desktop.macappstore.audio.jnaAudioPlayback
import dev.puklic.desktop.macappstore.codec.JnaLibopusDecoder
import dev.puklic.desktop.macappstore.codec.JnaLibopusEncoder
import dev.puklic.desktop.macappstore.transport.JnaNwConnectionUdpTransportFactory
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.session.adapter.MainGatewayBridgeAdapter
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.client.AudioEndpoints
import dev.puklic.voice.client.OpusEndpoints
import dev.puklic.voice.client.SharedVoiceClient
import dev.puklic.voice.client.VoicePlatform
import dev.puklic.voice.client.VoiceSignalling
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.gateway.ktorVoiceGatewayTransportFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The per-session main-gateway pieces the Mac App Store voice engine is wired to. */
internal class MacAppStoreVoiceWiring(
    val sessionScope: CoroutineScope,
    val gateway: GatewayConnection,
    val gatewayBridge: DiscordGatewayBridge,
    val selfUserId: StateFlow<UserId?>,
    val rest: DiscordRestClient,
    val messageBridge: DiscordMessageBridge,
)

/**
 * Builds the Mac App Store voice client: the shared DAVE-free [SharedVoiceClient] running on the
 * JNA-bridged Apple primitives (AVAudioEngine, libopus, Network.framework). The JVM FFmpeg Opus
 * actual is not on this build's classpath, so the libopus binding is passed in explicitly.
 */
@OptIn(PuklicVoiceCodec::class)
internal fun buildMacAppStoreVoiceClient(wiring: MacAppStoreVoiceWiring, httpClient: HttpClient): VoiceClient {
    val mainGateway = MainGatewayBridgeAdapter(
        gateway = wiring.gateway,
        bridge = wiring.gatewayBridge,
        scope = wiring.sessionScope,
        restClient = wiring.rest,
        messageBridge = wiring.messageBridge,
    )
    return SharedVoiceClient(
        applicationScope = wiring.sessionScope,
        signalling = VoiceSignalling(mainGateway) { wiring.selfUserId.value },
        platform = VoicePlatform(
            udpTransportFactory = JnaNwConnectionUdpTransportFactory,
            wsTransportFactory = ktorVoiceGatewayTransportFactory(httpClient),
            audio = AudioEndpoints(captureFactory = ::jnaAudioCapture, playbackFactory = ::jnaAudioPlayback),
            opus = OpusEndpoints(
                encoderFactory = { JnaLibopusEncoder() },
                decoderFactory = { JnaLibopusDecoder() },
            ),
        ),
    )
}
