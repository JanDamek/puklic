package dev.puklic.desktop

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.MessageId
import dev.puklic.ids.UserId
import dev.puklic.protocol.discord.DiscordDomainEvent
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.protocol.discord.DiscordMessageBridge
import dev.puklic.protocol.discord.gateway.GatewayConnection
import dev.puklic.protocol.discord.rest.DiscordRestClient
import dev.puklic.voice.MainGatewayBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Adapts the main Discord gateway (`GatewayConnection` + `DiscordGatewayBridge`) to the slim
 * [MainGatewayBridge] interface consumed by [dev.puklic.voice.DefaultVoiceClient].
 *
 * Keeps `:shared:voice` free of any direct dependency on `:shared:protocol-discord` or
 * `:shared:repositories`.
 */
internal class MainGatewayBridgeAdapter(
    private val gateway: GatewayConnection,
    bridge: DiscordGatewayBridge,
    scope: CoroutineScope,
    private val restClient: DiscordRestClient,
    private val messageBridge: DiscordMessageBridge,
) : MainGatewayBridge {

    private val _voiceStateUpdates =
        MutableSharedFlow<MainGatewayBridge.VoiceStateUpdate>(
            replay = 0,
            extraBufferCapacity = BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val voiceStateUpdates: SharedFlow<MainGatewayBridge.VoiceStateUpdate> =
        _voiceStateUpdates.asSharedFlow()

    private val _voiceServerUpdates =
        MutableSharedFlow<MainGatewayBridge.VoiceServerUpdate>(
            replay = 0,
            extraBufferCapacity = BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val voiceServerUpdates: SharedFlow<MainGatewayBridge.VoiceServerUpdate> =
        _voiceServerUpdates.asSharedFlow()

    private val _callEvents =
        MutableSharedFlow<MainGatewayBridge.CallEvent>(
            replay = 0,
            extraBufferCapacity = BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    override val callEvents: SharedFlow<MainGatewayBridge.CallEvent> = _callEvents.asSharedFlow()

    init {
        bridge.events
            .filterIsInstance<DiscordDomainEvent.VoiceStateUpdated>()
            .onEach {
                _voiceStateUpdates.emit(
                    MainGatewayBridge.VoiceStateUpdate(
                        guildId = it.guildId,
                        channelId = it.channelId,
                        userId = it.userId,
                        sessionId = it.sessionId,
                    ),
                )
            }
            .launchIn(scope)
        bridge.events
            .filterIsInstance<DiscordDomainEvent.VoiceServerUpdated>()
            .onEach {
                _voiceServerUpdates.emit(
                    MainGatewayBridge.VoiceServerUpdate(
                        guildId = it.guildId,
                        token = it.token,
                        endpoint = it.endpoint,
                    ),
                )
            }
            .launchIn(scope)
    }

    init {
        bridge.events
            .filterIsInstance<DiscordDomainEvent.CallStarted>()
            .onEach {
                _callEvents.emit(
                    MainGatewayBridge.CallEvent.Started(
                        channelId = it.channelId,
                        callerId = it.callerId,
                        messageId = it.messageId,
                        ringing = it.ringing,
                    ),
                )
            }
            .launchIn(scope)
        bridge.events
            .filterIsInstance<DiscordDomainEvent.CallRingingUpdated>()
            .onEach {
                _callEvents.emit(
                    MainGatewayBridge.CallEvent.RingingUpdated(
                        channelId = it.channelId,
                        ringing = it.ringing,
                    ),
                )
            }
            .launchIn(scope)
        bridge.events
            .filterIsInstance<DiscordDomainEvent.CallEnded>()
            .onEach {
                _callEvents.emit(
                    MainGatewayBridge.CallEvent.Ended(
                        channelId = it.channelId,
                        unavailable = it.unavailable,
                    ),
                )
            }
            .launchIn(scope)
    }

    override suspend fun stopRinging(channelId: ChannelId, recipients: List<UserId>) {
        // REST layer already swallows 404/410 internally; just ignore Result failure here.
        restClient.stopRinging(channelId, recipients).getOrThrow()
    }

    override suspend fun resolveMessageAuthor(channelId: ChannelId, messageId: MessageId): UserId? =
        messageBridge.fetchMessageAuthor(channelId, messageId)

    override suspend fun sendVoiceStateUpdate(
        guildId: GuildId?,
        channelId: ChannelId?,
        selfMute: Boolean,
        selfDeaf: Boolean,
    ) {
        gateway.sendVoiceStateUpdate(guildId, channelId, selfMute, selfDeaf)
    }

    private companion object {
        const val BUFFER = 16
    }
}
