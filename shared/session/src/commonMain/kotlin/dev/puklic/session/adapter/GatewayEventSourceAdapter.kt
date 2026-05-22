package dev.puklic.session.adapter

import dev.puklic.protocol.discord.DiscordDomainEvent
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.repositories.GatewayDomainEvent
import dev.puklic.repositories.GatewayEventSource
import dev.puklic.repositories.PresenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val EVENT_BUFFER = 64

/**
 * Adapts the protocol-layer [DiscordGatewayBridge] (which emits [DiscordDomainEvent]) to the
 * repositories-layer [GatewayEventSource] (which emits [GatewayDomainEvent]). The bridge does the
 * DTO→domain translation; this adapter only narrows the event vocabulary to what the orchestrators
 * subscribe to (messages, presence, typing). Other event kinds (guild / channel / user / ready)
 * flow through the bridge for the wiring layer to consume directly.
 */
public class GatewayEventSourceAdapter(
    private val bridge: DiscordGatewayBridge,
    scope: CoroutineScope,
) : GatewayEventSource {
    private val _events = MutableSharedFlow<GatewayDomainEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: SharedFlow<GatewayDomainEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            bridge.events.collect { ev ->
                val mapped = when (ev) {
                    is DiscordDomainEvent.MessageCreated -> GatewayDomainEvent.MessageCreated(ev.message)
                    is DiscordDomainEvent.MessageUpdated -> GatewayDomainEvent.MessageUpdated(ev.message)
                    is DiscordDomainEvent.MessageDeleted -> GatewayDomainEvent.MessageDeleted(ev.channelId, ev.messageId)
                    is DiscordDomainEvent.PresenceUpdated -> GatewayDomainEvent.PresenceUpdated(
                        userId = ev.userId,
                        state = parsePresence(ev.rawStatus),
                    )
                    is DiscordDomainEvent.TypingStarted -> GatewayDomainEvent.TypingStarted(
                        channelId = ev.channelId,
                        userId = ev.userId,
                        timestampEpochSeconds = ev.timestampEpochSeconds,
                    )
                    is DiscordDomainEvent.GuildCreated -> GatewayDomainEvent.GuildCreated(ev.guild)
                    is DiscordDomainEvent.GuildUpdated -> GatewayDomainEvent.GuildUpdated(ev.guild)
                    is DiscordDomainEvent.GuildDeleted -> GatewayDomainEvent.GuildDeleted(ev.guildId)
                    is DiscordDomainEvent.ChannelCreated -> GatewayDomainEvent.ChannelCreated(ev.channel)
                    is DiscordDomainEvent.ChannelUpdated -> GatewayDomainEvent.ChannelUpdated(ev.channel)
                    is DiscordDomainEvent.ChannelDeleted -> GatewayDomainEvent.ChannelDeleted(ev.channelId)
                    is DiscordDomainEvent.UserUpdated -> GatewayDomainEvent.UserUpdated(ev.user)
                    is DiscordDomainEvent.Ready -> GatewayDomainEvent.Ready(ev.selfUser, ev.sessionId)
                }
                _events.tryEmit(mapped)
            }
        }
    }

    private fun parsePresence(raw: String): PresenceState = when (raw.lowercase()) {
        "online" -> PresenceState.ONLINE
        "idle" -> PresenceState.IDLE
        "dnd" -> PresenceState.DO_NOT_DISTURB
        "invisible" -> PresenceState.INVISIBLE
        else -> PresenceState.OFFLINE
    }
}
