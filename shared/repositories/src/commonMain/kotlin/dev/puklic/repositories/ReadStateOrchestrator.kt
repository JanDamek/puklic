package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.ids.ChannelId
import dev.puklic.ids.MessageId
import dev.puklic.persistence.repository.ReadState
import dev.puklic.persistence.repository.ReadStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val LOG_TAG = "ReadStateOrchestrator"

/**
 * Per-channel unread / mention view exposed to the UI. (Issue #81.)
 *
 * - [mentionCount] = number of unread mentions in this channel (READY snapshot, updated by
 *   `MESSAGE_ACK` dispatches).
 * - [hasUnread] = there is at least one unread message in the channel. For the MVP it tracks
 *   `mentionCount > 0`; once the orchestrator also subscribes to `MESSAGE_CREATE` and
 *   compares the latest cached message id against [ReadState.lastMessageId] it will surface
 *   non-mention unread too.
 */
public data class ReadStateView(
    val mentionCount: Int,
    val hasUnread: Boolean,
)

/**
 * Subscribes to [GatewayEventSource]'s [GatewayDomainEvent.ReadStatesReplaced] (READY snapshot)
 * and [GatewayDomainEvent.ReadStateAck] (incremental updates from Discord's `MESSAGE_ACK`
 * dispatch) and maintains a per-channel unread / mention view. Also persists every entry to
 * the [ReadStateRepository] so the unread state survives a cold start. (Issue #81.)
 */
public class ReadStateOrchestrator(
    sessionScope: CoroutineScope,
    gatewaySource: GatewayEventSource,
    private val storage: ReadStateRepository,
) {
    private val _byChannel = MutableStateFlow<Map<ChannelId, ReadStateView>>(emptyMap())

    /** Per-channel read-state view for the UI. Empty until READY arrives. */
    public val byChannel: StateFlow<Map<ChannelId, ReadStateView>> = _byChannel.asStateFlow()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent.ReadStatesReplaced>()
            .onEach { onReplaced(it.entries) }
            .launchIn(sessionScope)
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent.ReadStateAck>()
            .onEach { onAck(it.channelId, it.lastReadMessageId, it.mentionCount) }
            .launchIn(sessionScope)

        // Hydrate from persisted state on construction so the rail shows mentions immediately
        // even before READY repopulates them.
        sessionScope.launch { hydrate() }
    }

    private suspend fun hydrate() {
        val persisted = storage.findAll()
        if (persisted.isEmpty()) return
        val map = persisted.associate { state ->
            state.channelId to ReadStateView(
                mentionCount = state.mentionCount,
                hasUnread = state.mentionCount > 0,
            )
        }
        _byChannel.value = map
        Logger.d(LOG_TAG) { "hydrated read-state count=${map.size}" }
    }

    private suspend fun onReplaced(entries: List<GatewayDomainEvent.ReadStateEntry>) {
        Logger.d(LOG_TAG) { "READY read_state size=${entries.size}" }
        val next = entries.associate { entry ->
            entry.channelId to ReadStateView(
                mentionCount = entry.mentionCount,
                hasUnread = entry.mentionCount > 0,
            )
        }
        _byChannel.value = next
        for (entry in entries) {
            storage.persist(
                ReadState(
                    channelId = entry.channelId,
                    lastMessageId = entry.lastReadMessageId,
                    mentionCount = entry.mentionCount,
                    lastViewedAt = null,
                ),
            )
        }
    }

    private suspend fun onAck(channelId: ChannelId, lastRead: MessageId?, mentionCount: Int) {
        Logger.d(LOG_TAG) { "ACK channel=${channelId.value} mention=$mentionCount" }
        _byChannel.value = _byChannel.value + (
            channelId to ReadStateView(
                mentionCount = mentionCount,
                hasUnread = mentionCount > 0,
            )
        )
        storage.persist(
            ReadState(
                channelId = channelId,
                lastMessageId = lastRead,
                mentionCount = mentionCount,
                lastViewedAt = null,
            ),
        )
    }
}
