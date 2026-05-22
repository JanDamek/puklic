package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.UserSummary
import dev.puklic.persistence.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val USER_ORCH_TAG = "UserOrchestrator"

/**
 * Subscribes to [GatewayEventSource] user-scope events and mirrors them into [storage].
 * Tracks the self user surfaced by [GatewayDomainEvent.Ready] and exposes it as [selfUser].
 */
public class UserOrchestrator(
    sessionScope: CoroutineScope,
    gatewaySource: GatewayEventSource,
    private val storage: UserRepository,
) {
    private val _selfUser = MutableStateFlow<UserSummary?>(null)
    public val selfUser: StateFlow<UserSummary?> = _selfUser.asStateFlow()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
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
            .catch { t ->
                Logger.w(USER_ORCH_TAG) {
                    "user orchestrator: flow error caught, will not propagate " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    /** Synchronous cache lookup against [storage]. */
    public suspend fun findById(id: dev.puklic.ids.UserId): UserSummary? = storage.findById(id)
}
