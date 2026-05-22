package dev.puklic.repositories

import dev.puklic.domain.Guild
import dev.puklic.persistence.repository.GuildRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Subscribes to [GatewayEventSource] guild lifecycle events and mirrors them into [storage].
 * Exposes a reactive [guilds] StateFlow backed by [GuildRepository.observeAll] for the UI.
 */
public class GuildOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: GuildRepository,
) {
    public val guilds: StateFlow<List<Guild>> =
        storage.observeAll().stateIn(sessionScope, SharingStarted.Eagerly, emptyList())

    public val all: Flow<List<Guild>> get() = storage.observeAll()

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                when (event) {
                    is GatewayDomainEvent.GuildCreated -> storage.persist(event.guild)
                    is GatewayDomainEvent.GuildUpdated -> storage.persist(event.guild)
                    is GatewayDomainEvent.GuildDeleted -> storage.delete(event.guildId)
                    else -> Unit
                }
            }
        }
    }
}
