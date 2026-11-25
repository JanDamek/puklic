@file:OptIn(PuklicVoiceCodec::class)

package dev.puklic.android

import dev.puklic.ids.UserId
import dev.puklic.platform.android.voice.PermissionGatedVoiceClient
import dev.puklic.platform.android.voice.VoiceServiceController
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
import dev.puklic.voice.codec.transport.JvmVoiceUdpTransportFactory
import dev.puklic.voice.gateway.ktorVoiceGatewayTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The per-session main-gateway pieces the voice engine is wired to. */
internal class VoiceWiring(
    val sessionScope: CoroutineScope,
    val gateway: GatewayConnection,
    val gatewayBridge: DiscordGatewayBridge,
    val selfUserId: StateFlow<UserId?>,
    val rest: DiscordRestClient,
    val messageBridge: DiscordMessageBridge,
)

/**
 * Builds the Android voice client: the shared DAVE-free [SharedVoiceClient] on top of the Android
 * audio, Opus and UDP actuals, wrapped so that the microphone permission is requested on the first
 * join and the foreground service plus the communication audio route follow the session state.
 */
internal fun buildVoiceClient(wiring: VoiceWiring, deps: SessionDependencies): VoiceClient {
    val mainGateway = MainGatewayBridgeAdapter(
        gateway = wiring.gateway,
        bridge = wiring.gatewayBridge,
        scope = wiring.sessionScope,
        restClient = wiring.rest,
        messageBridge = wiring.messageBridge,
    )
    val engine = SharedVoiceClient(
        applicationScope = wiring.sessionScope,
        signalling = VoiceSignalling(mainGateway) { wiring.selfUserId.value },
        platform = VoicePlatform(
            udpTransportFactory = JvmVoiceUdpTransportFactory,
            wsTransportFactory = ktorVoiceGatewayTransportFactory(deps.httpClient),
        ),
    )
    val client = PermissionGatedVoiceClient(engine, deps.permissions)
    VoiceServiceController(deps.context, wiring.sessionScope).bind(client)
    return client
}
