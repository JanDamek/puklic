package dev.puklic.repositories

import dev.puklic.domain.UserSummary
import dev.puklic.persistence.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Subscribes to [GatewayEventSource] user-scope events and mirrors them into [storage].
 * Tracks the self user surfaced by [GatewayDomainEvent.Ready] and exposes it as [selfUser].
 */
public class UserOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: UserRepository,
) {
    private val _selfUser = MutableStateFlow<UserSummary?>(null)
    public val selfUser: StateFlow<UserSummary?> = _selfUser.asStateFlow()

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                when (event) {
                    is GatewayDomainEvent.Ready -> {
                        _selfUser.value = event.selfUser
                        storage.persist(event.selfUser)
                    }
                    is GatewayDomainEvent.UserUpdated -> {
                        storage.persist(event.user)
                        if (_selfUser.value?.id == event.user.id) _selfUser.value = event.user
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Synchronous cache lookup against [storage]. */
    public suspend fun findById(id: dev.puklic.ids.UserId): UserSummary? = storage.findById(id)
}
