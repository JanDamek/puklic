package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

private const val PRESENCE_TAG = "PresenceOrchestrator"

/**
 * Tracks user presence in RAM. Ephemeral — never persisted (ADR-0003).
 *
 * Subscribes to [GatewayEventSource.events] and maintains a `Map<UserId, PresenceState>` reactive
 * snapshot. Consumers observe [presences].
 */
public class PresenceOrchestrator(
    sessionScope: CoroutineScope,
    gatewaySource: GatewayEventSource,
) {
    private val state = MutableStateFlow<Map<UserId, PresenceState>>(emptyMap())
    public val presences: StateFlow<Map<UserId, PresenceState>> = state.asStateFlow()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent.PresenceUpdated>()
            .onEach { event ->
                state.update { current ->
                    if (event.state == PresenceState.OFFLINE) {
                        current - event.userId
                    } else {
                        current + (event.userId to event.state)
                    }
                }
            }
            .catch { t ->
                Logger.w(PRESENCE_TAG) {
                    "presence orchestrator: flow error caught " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    /** Synchronous lookup. */
    public fun presenceOf(userId: UserId): PresenceState = state.value[userId] ?: PresenceState.OFFLINE
}
