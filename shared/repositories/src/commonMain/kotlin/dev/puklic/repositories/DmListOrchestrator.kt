package dev.puklic.repositories

import dev.puklic.domain.DmChannel
import dev.puklic.ids.ChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tracks the user's Direct-Message channels (channel types 1 + 3) entirely in memory.
 *
 * DM channels are not persisted in the SQLite `channel` table for now — the table is keyed by
 * guild_id and the recipients hydration would require a join. Holding the list in a StateFlow
 * is sufficient for the UI: the gateway delivers the full set inside READY and per-channel
 * deltas via CHANNEL_CREATE / CHANNEL_DELETE.
 */
public class DmListOrchestrator(
    sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
) {
    private val _dms = MutableStateFlow<List<DmChannel>>(emptyList())
    public val dms: StateFlow<List<DmChannel>> = _dms.asStateFlow()

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                when (event) {
                    is GatewayDomainEvent.ChannelCreated -> if (event.channel is DmChannel) {
                        upsert(event.channel)
                    }
                    is GatewayDomainEvent.ChannelUpdated -> if (event.channel is DmChannel) {
                        upsert(event.channel)
                    }
                    is GatewayDomainEvent.ChannelDeleted -> remove(event.channelId)
                    else -> Unit
                }
            }
        }
    }

    private fun upsert(channel: DmChannel) {
        _dms.value = (_dms.value.filterNot { it.id == channel.id } + channel)
    }

    private fun remove(id: ChannelId) {
        _dms.value = _dms.value.filterNot { it.id == id }
    }
}
