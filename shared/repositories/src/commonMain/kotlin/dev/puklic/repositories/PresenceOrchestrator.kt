package dev.puklic.repositories

import dev.puklic.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tracks user presence in RAM. Ephemeral — never persisted (ADR-0003).
 *
 * Subscribes to [GatewayEventSource.events] and maintains a `Map<UserId, PresenceState>` reactive
 * snapshot. Consumers observe [presences].
 */
public class PresenceOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
) {
    private val state = MutableStateFlow<Map<UserId, PresenceState>>(emptyMap())
    public val presences: StateFlow<Map<UserId, PresenceState>> = state.asStateFlow()

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                if (event is GatewayDomainEvent.PresenceUpdated) {
                    state.update { current ->
                        if (event.state == PresenceState.OFFLINE) {
                            current - event.userId
                        } else {
                            current + (event.userId to event.state)
                        }
                    }
                }
            }
        }
    }

    /** Synchronous lookup. */
    public fun presenceOf(userId: UserId): PresenceState = state.value[userId] ?: PresenceState.OFFLINE
}
