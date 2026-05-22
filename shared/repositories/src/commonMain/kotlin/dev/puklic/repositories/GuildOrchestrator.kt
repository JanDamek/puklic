package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.Guild
import dev.puklic.persistence.repository.GuildRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Subscribes to [GatewayEventSource] guild lifecycle events and mirrors them into [storage].
 * Exposes a reactive [guilds] StateFlow backed by [GuildRepository.observeAll] for the UI.
 */
private const val LOG_TAG = "GuildOrchestrator"

public class GuildOrchestrator(
    sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: GuildRepository,
) {
    public val guilds: StateFlow<List<Guild>> =
        storage.observeAll().stateIn(sessionScope, SharingStarted.Eagerly, emptyList())

    public val all: Flow<List<Guild>> get() = storage.observeAll()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.GuildCreated -> persist(event.guild, op = "create")
                    is GatewayDomainEvent.GuildUpdated -> persist(event.guild, op = "update")
                    is GatewayDomainEvent.GuildDeleted -> storage.delete(event.guildId)
                    else -> Unit
                }
            }
            .catch { t ->
                Logger.w(LOG_TAG) {
                    "guild orchestrator: flow error caught, will not propagate " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .launchIn(sessionScope)
    }

    private suspend fun persist(guild: Guild, op: String) {
        try {
            storage.persist(guild)
            Logger.i(LOG_TAG) { "guild orchestrator: $op OK guild id=${guild.id.value} name=${guild.name}" }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG) {
                "guild orchestrator: $op FAILED guild id=${guild.id.value} " +
                    "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
            }
        }
    }
}
