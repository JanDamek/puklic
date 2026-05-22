package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.DmChannel
import dev.puklic.ids.ChannelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val DM_LIST_TAG = "DmListOrchestrator"

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
    gatewaySource: GatewayEventSource,
) {
    private val _dms = MutableStateFlow<List<DmChannel>>(emptyList())
    public val dms: StateFlow<List<DmChannel>> = _dms.asStateFlow()

    init {
        Logger.i(DM_LIST_TAG) { "dm-list orchestrator: subscribing to gateway events" }
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.ChannelCreated -> (event.channel as? DmChannel)?.let { upsert(it) }
                    is GatewayDomainEvent.ChannelUpdated -> (event.channel as? DmChannel)?.let { upsert(it) }
                    is GatewayDomainEvent.ChannelDeleted -> remove(event.channelId)
                    else -> Unit
                }
            }
            .catch { t ->
                Logger.w(DM_LIST_TAG) {
                    "dm-list orchestrator: flow error caught, will not propagate " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    private fun upsert(channel: DmChannel) {
        _dms.value = (_dms.value.filterNot { it.id == channel.id } + channel)
        Logger.i(DM_LIST_TAG) {
            "dm-list orchestrator: upsert -> dms.size=${_dms.value.size} id=${channel.id.value} " +
                "recipients=${channel.recipients.size}"
        }
    }

    private fun remove(id: ChannelId) {
        _dms.value = _dms.value.filterNot { it.id == id }
    }
}
