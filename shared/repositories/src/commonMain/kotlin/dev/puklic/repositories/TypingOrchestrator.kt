package dev.puklic.repositories

import dev.puklic.ids.ChannelId
import dev.puklic.ids.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tracks per-channel typing indicators in RAM. Ephemeral — never persisted (ADR-0003).
 *
 * A TYPING_START indicator auto-clears after [TYPING_TTL_SECONDS] seconds. The caller must
 * periodically invoke [tickExpiry] (e.g. from a screen-driven ticker) to evict stale entries —
 * the orchestrator does not own a timer to avoid pinning the dispatcher when no UI subscribes.
 */
public class TypingOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val nowEpochSeconds: () -> Long,
) {
    private data class TypingEntry(val expiresAtEpochSeconds: Long)

    private val internalState = MutableStateFlow<Map<ChannelId, Map<UserId, TypingEntry>>>(emptyMap())

    /** Reactive map: channel -> set of users currently typing. */
    public val typing: StateFlow<Map<ChannelId, Set<UserId>>> =
        internalState
            .map { snap -> snap.mapValues { (_, users) -> users.keys.toSet() } }
            .stateIn(sessionScope, SharingStarted.Eagerly, emptyMap())

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                if (event is GatewayDomainEvent.TypingStarted) {
                    val expiresAt = event.timestampEpochSeconds + TYPING_TTL_SECONDS
                    internalState.update { current ->
                        val perChannel = current[event.channelId].orEmpty() +
                            (event.userId to TypingEntry(expiresAt))
                        current + (event.channelId to perChannel)
                    }
                }
            }
        }
    }

    /** Evict typing entries whose TTL has expired relative to [nowEpochSeconds]. */
    public fun tickExpiry() {
        val now = nowEpochSeconds()
        internalState.update { current ->
            current.mapValues { (_, users) -> users.filterValues { it.expiresAtEpochSeconds > now } }
                .filterValues { it.isNotEmpty() }
        }
    }

    public companion object {
        public const val TYPING_TTL_SECONDS: Long = 10L
    }
}
