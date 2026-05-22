package dev.puklic.repositories

import dev.puklic.domain.Channel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.GuildId
import dev.puklic.persistence.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Subscribes to [GatewayEventSource] channel lifecycle events and mirrors them into [storage].
 * Exposes per-guild reactive Flow via [channelsForGuild].
 */
public class ChannelOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: ChannelRepository,
) {
    /** Reactive channel list for [guildId], backed by SQLite. */
    public fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> =
        storage.observeByGuild(guildId)

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                when (event) {
                    is GatewayDomainEvent.ChannelCreated -> persistIfGuildChannel(event.channel)
                    is GatewayDomainEvent.ChannelUpdated -> persistIfGuildChannel(event.channel)
                    is GatewayDomainEvent.ChannelDeleted -> storage.delete(event.channelId)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun persistIfGuildChannel(channel: Channel) {
        // Only guild-scoped channels go through ChannelRepository; DM channels are not part of
        // the per-guild list and are handled separately (out of Phase 1 scope).
        if (channel is GuildTextChannel) storage.persist(channel)
    }
}
