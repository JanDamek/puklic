package dev.puklic.session.adapter

import dev.puklic.protocol.discord.DiscordError
import dev.puklic.protocol.discord.DiscordSessionBridge
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.gateway.GatewayState
import dev.puklic.session.GatewayLifecycleEvent
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.session.SessionTransport
import dev.puklic.session.TokenValidation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Real [SessionTransport] backed by a [DiscordSessionBridge] (REST) plus a [GatewayConnection]
 * (WebSocket). Token is supplied at construction time — the per-call `token` parameter on the
 * interface is treated as a redundant assertion; the stored token is authoritative because the
 * gateway and REST client are bound to it.
 *
 * Lifecycle:
 *  - [validateToken] → bridge `getSelfUser` ⇒ `Ok | Unauthorized | TransportError`
 *  - [connectGateway] → starts the [GatewayConnection]; a coroutine on [scope] forwards its
 *    [GatewayState] transitions onto [lifecycle].
 *  - [disconnectGateway] → cancels the connection cleanly.
 */
public class SessionTransportImpl(
    private val bridge: DiscordSessionBridge,
    private val gateway: GatewayConnection,
    scope: CoroutineScope,
) : SessionTransport {

    private val _lifecycle = MutableSharedFlow<GatewayLifecycleEvent>(replay = 0, extraBufferCapacity = LIFECYCLE_BUFFER)
    override val lifecycle: SharedFlow<GatewayLifecycleEvent> = _lifecycle.asSharedFlow()

    init {
        scope.launch {
            gateway.state.collect { state ->
                val event = mapGatewayState(state) ?: return@collect
                _lifecycle.tryEmit(event)
            }
        }
    }

    override suspend fun validateToken(token: String): TokenValidation {
        val result = bridge.fetchSelfUser()
        return result.fold(
            onSuccess = { TokenValidation.Ok(it) },
            onFailure = { cause ->
                val msg = cause.message.orEmpty()
                val tokenInvalid = cause as? DiscordError.TokenInvalid
                if (tokenInvalid != null || msg.contains("401")) {
                    TokenValidation.Unauthorized(tokenInvalid?.detail.orEmpty().ifBlank { msg })
                } else {
                    TokenValidation.TransportError(msg.ifBlank { cause::class.simpleName.orEmpty() })
                }
            },
        )
    }

    override suspend fun connectGateway(token: String) {
        gateway.connect()
    }

    override suspend fun disconnectGateway() {
        gateway.disconnect()
    }

    override suspend fun lazyRequestGuild(guildId: GuildId, channelIds: List<ChannelId>) {
        gateway.lazyRequestGuild(guildId, channelIds)
    }

    override suspend fun markChannelRead(channelId: ChannelId, messageId: MessageId) {
        bridge.markChannelRead(channelId, messageId)
    }

    private fun mapGatewayState(state: GatewayState): GatewayLifecycleEvent? = when (state) {
        is GatewayState.Ready -> GatewayLifecycleEvent.Connected(state.sessionId)
        is GatewayState.Disconnected -> GatewayLifecycleEvent.Disconnected
        is GatewayState.TokenInvalid -> GatewayLifecycleEvent.TokenInvalid
        is GatewayState.Resuming -> GatewayLifecycleEvent.Reconnecting(secondsUntilRetry = 0)
        // Connecting / HelloReceived / Identifying are intermediate — do not surface as lifecycle.
        else -> null
    }

    private companion object {
        const val LIFECYCLE_BUFFER = 8
    }
}
