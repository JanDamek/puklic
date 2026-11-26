package dev.puklic.ios

import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.session.adapter.MainGatewayBridgeAdapter
import dev.puklic.voice.VoiceClient
import dev.puklic.voice.client.SharedVoiceClient
import dev.puklic.voice.client.VoicePlatform
import dev.puklic.voice.client.VoiceSignalling
import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.IosVoiceUdpTransportFactory
import dev.puklic.voice.gateway.ktorVoiceGatewayTransportFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The per-session main-gateway pieces the iOS voice engine is wired to. */
internal class IosVoiceWiring(
    val sessionScope: CoroutineScope,
    val gateway: GatewayConnection,
    val gatewayBridge: DiscordGatewayBridge,
    val selfUserId: StateFlow<UserId?>,
    val rest: DiscordRestClient,
    val messageBridge: DiscordMessageBridge,
)

/**
 * Builds the iOS / iPadOS voice client: the shared DAVE-free [SharedVoiceClient] running on the
 * AVAudioEngine capture and playback, libopus and Network.framework UDP actuals of
 * `:shared:voice-codec`.
 */
@OptIn(PuklicVoiceCodec::class)
internal fun buildIosVoiceClient(wiring: IosVoiceWiring, httpClient: HttpClient): VoiceClient {
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
            udpTransportFactory = IosVoiceUdpTransportFactory,
            wsTransportFactory = ktorVoiceGatewayTransportFactory(httpClient),
        ),
    )
}
