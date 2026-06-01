package dev.puklic.session.adapter

import co.touchlab.kermit.Logger
import dev.puklic.protocol.discord.DiscordDomainEvent
import dev.puklic.protocol.discord.DiscordGatewayBridge
import dev.puklic.repositories.GatewayDomainEvent
import dev.puklic.repositories.GatewayEventSource
import dev.puklic.repositories.PresenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Buffer capacity for the SharedFlow that fans out domain events to all orchestrators.
 *
 * Discord's READY event can synthesize a burst of 200+ ChannelCreated emissions (one per channel
 * across every guild). With the previous capacity of 64 and the default `tryEmit` semantics,
 * events past index 64 were silently dropped — which is the exact bug shape ("ChannelOrchestrator
 * persisted ~50 then stopped"). Raise the buffer well above any realistic burst.
 */
private const val EVENT_BUFFER = 1024
private const val ADAPTER_TAG = "GatewayEventSourceAdapter"

/**
 * Adapts the protocol-layer [DiscordGatewayBridge] (which emits [DiscordDomainEvent]) to the
 * repositories-layer [GatewayEventSource] (which emits [GatewayDomainEvent]). The bridge does the
 * DTO->domain translation; this adapter only narrows the event vocabulary to what the orchestrators
 * subscribe to.
 *
 * Implementation is Flow-first: the bridge.events Flow is mapped + collected via .onEach +
 * .launchIn(scope). Emission uses suspending `emit` (not `tryEmit`) so back-pressure is honored
 * end-to-end and no event is ever silently dropped.
 */
public class GatewayEventSourceAdapter(
    bridge: DiscordGatewayBridge,
    scope: CoroutineScope,
) : GatewayEventSource {
    private val _events = MutableSharedFlow<GatewayDomainEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<GatewayDomainEvent> = _events.asSharedFlow()

    init {
        bridge.events
            .onEach { ev ->
                Logger.i(ADAPTER_TAG) { "adapter: forwarding domain event = ${ev::class.simpleName}" }
                _events.emit(map(ev))
            }
            .catch { t ->
                Logger.w(ADAPTER_TAG) {
                    "adapter: flow error caught cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(scope)
    }

    private fun map(ev: DiscordDomainEvent): GatewayDomainEvent = when (ev) {
        is DiscordDomainEvent.MessageCreated -> GatewayDomainEvent.MessageCreated(ev.message)
        is DiscordDomainEvent.MessageUpdated -> GatewayDomainEvent.MessageUpdated(ev.message)
        is DiscordDomainEvent.MessageUpdatedPartial -> GatewayDomainEvent.MessageUpdatedPartial(
            messageId = ev.messageId,
            channelId = ev.channelId,
            content = ev.content,
            editedTimestampEpochMs = ev.editedTimestampEpochMs,
            flags = ev.flags,
            pinned = ev.pinned,
            embeds = ev.embeds,
            attachments = ev.attachments,
        )
        is DiscordDomainEvent.MessageDeleted -> GatewayDomainEvent.MessageDeleted(ev.channelId, ev.messageId)
        is DiscordDomainEvent.MessageDeletedBulk -> GatewayDomainEvent.MessageDeletedBulk(ev.channelId, ev.messageIds)
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
        is DiscordDomainEvent.GuildPositionsUpdated ->
            GatewayDomainEvent.GuildPositionsUpdated(ev.positions)
        is DiscordDomainEvent.ReadStatesReplaced ->
            GatewayDomainEvent.ReadStatesReplaced(
                ev.entries.map {
                    GatewayDomainEvent.ReadStateEntry(it.channelId, it.lastReadMessageId, it.mentionCount)
                },
            )
        is DiscordDomainEvent.ReadStateAck ->
            GatewayDomainEvent.ReadStateAck(ev.channelId, ev.lastReadMessageId, ev.mentionCount)
        is DiscordDomainEvent.ChannelCreated -> GatewayDomainEvent.ChannelCreated(ev.channel)
        is DiscordDomainEvent.ChannelUpdated -> GatewayDomainEvent.ChannelUpdated(ev.channel)
        is DiscordDomainEvent.ChannelDeleted -> GatewayDomainEvent.ChannelDeleted(ev.channelId)
        is DiscordDomainEvent.UserUpdated -> GatewayDomainEvent.UserUpdated(ev.user)
        is DiscordDomainEvent.Ready -> GatewayDomainEvent.Ready(ev.selfUser, ev.sessionId, ev.users)
        is DiscordDomainEvent.ReactionAdded -> GatewayDomainEvent.ReactionAdded(
            channelId = ev.channelId,
            messageId = ev.messageId,
            userId = ev.userId,
            emoji = ev.emoji,
        )
        is DiscordDomainEvent.ReactionRemoved -> GatewayDomainEvent.ReactionRemoved(
            channelId = ev.channelId,
            messageId = ev.messageId,
            userId = ev.userId,
            emoji = ev.emoji,
        )
        is DiscordDomainEvent.ReactionsClearedAll -> GatewayDomainEvent.ReactionsClearedAll(
            channelId = ev.channelId,
            messageId = ev.messageId,
        )
        is DiscordDomainEvent.ReactionsClearedEmoji -> GatewayDomainEvent.ReactionsClearedEmoji(
            channelId = ev.channelId,
            messageId = ev.messageId,
            emoji = ev.emoji,
        )
        is DiscordDomainEvent.VoiceStateUpdated -> GatewayDomainEvent.VoiceStateUpdated(
            guildId = ev.guildId,
            channelId = ev.channelId,
            userId = ev.userId,
            sessionId = ev.sessionId,
            selfMute = ev.selfMute,
            selfDeaf = ev.selfDeaf,
            mute = ev.mute,
            deaf = ev.deaf,
        )
        is DiscordDomainEvent.VoiceServerUpdated -> GatewayDomainEvent.VoiceServerUpdated(
            guildId = ev.guildId,
            token = ev.token,
            endpoint = ev.endpoint,
        )
        is DiscordDomainEvent.VoiceStatesBootstrap -> GatewayDomainEvent.VoiceStatesBootstrap(
            guildId = ev.guildId,
            states = ev.states,
        )
        is DiscordDomainEvent.GuildRolesSnapshot -> GatewayDomainEvent.GuildRolesSnapshot(ev.guildId, ev.roles)
        is DiscordDomainEvent.RoleCreated -> GatewayDomainEvent.RoleCreated(ev.role)
        is DiscordDomainEvent.RoleUpdated -> GatewayDomainEvent.RoleUpdated(ev.role)
        is DiscordDomainEvent.RoleDeleted -> GatewayDomainEvent.RoleDeleted(ev.guildId, ev.roleId)
        is DiscordDomainEvent.SelfMemberUpdated -> GatewayDomainEvent.SelfMemberUpdated(ev.member)
        is DiscordDomainEvent.CallStarted -> GatewayDomainEvent.CallStarted(
            channelId = ev.channelId,
            callerId = ev.callerId,
            messageId = ev.messageId,
            ringing = ev.ringing,
            region = ev.region,
        )
        is DiscordDomainEvent.CallRingingUpdated -> GatewayDomainEvent.CallRingingUpdated(
            channelId = ev.channelId,
            ringing = ev.ringing,
        )
        is DiscordDomainEvent.CallEnded -> GatewayDomainEvent.CallEnded(
            channelId = ev.channelId,
            unavailable = ev.unavailable,
        )
    }

    private fun parsePresence(raw: String): PresenceState = when (raw.lowercase()) {
        "online" -> PresenceState.ONLINE
        "idle" -> PresenceState.IDLE
        "dnd" -> PresenceState.DO_NOT_DISTURB
        "invisible" -> PresenceState.INVISIBLE
        else -> PresenceState.OFFLINE
    }
}
