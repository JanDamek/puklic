package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.Guild
import dev.puklic.ids.GuildId
import dev.puklic.persistence.repository.GuildRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Subscribes to [GatewayEventSource] guild lifecycle events and mirrors them into [storage].
 * Exposes a reactive [guilds] StateFlow backed by [GuildRepository.observeAll] for the UI.
 *
 * Issue #85 — the orchestrator also tracks user-account guild ordering (`GuildPositionsUpdated`
 * from READY's `user_settings`) and exposes [orderedGuilds] which applies that ordering to the
 * persisted guild list. Guilds with no position fall back to alphabetical name order at the end
 * of the rail so the user never loses access to a server.
 */
private const val LOG_TAG = "GuildOrchestrator"

public class GuildOrchestrator(
    sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: GuildRepository,
) {
    private val _guildPositions = MutableStateFlow<List<GuildId>>(emptyList())

    public val guildPositions: StateFlow<List<GuildId>> = _guildPositions.asStateFlow()

    /**
     * Persisted guilds ordered by the user-account ordering. Stable across screen rotations and
     * READY redeliveries. Falls back to alphabetical name order until READY supplies positions.
     */
    public val orderedGuilds: StateFlow<List<Guild>> =
        storage.observeAll()
            .combine(_guildPositions) { all, positions -> applyOrdering(all, positions) }
            .stateIn(sessionScope, SharingStarted.Eagerly, emptyList())

    /**
     * Raw guild list ordered by [orderedGuilds] semantics. Kept as a separate property so that
     * existing callers (which previously observed alphabetical order) automatically pick up the
     * user-account ordering without UI-side rewrites.
     */
    public val guilds: StateFlow<List<Guild>> get() = orderedGuilds

    public val all: Flow<List<Guild>> get() = storage.observeAll()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.GuildCreated -> persist(event.guild, op = "create")
                    is GatewayDomainEvent.GuildUpdated -> persist(event.guild, op = "update")
                    is GatewayDomainEvent.GuildDeleted -> storage.delete(event.guildId)
                    is GatewayDomainEvent.GuildPositionsUpdated -> {
                        Logger.i(LOG_TAG) {
                            "guild orchestrator: positions updated, size=${event.positions.size}"
                        }
                        _guildPositions.value = event.positions
                    }
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

    public companion object {
        /**
         * Apply user-account ordering [positions] to [guilds]. Guilds whose id appears in
         * [positions] take that exact slot order; remaining guilds are appended in alphabetical
         * name order so unknown / newly-joined guilds never silently disappear. Empty positions
         * means "no user ordering known yet" — fall back to alphabetical only.
         */
        public fun applyOrdering(guilds: List<Guild>, positions: List<GuildId>): List<Guild> {
            if (guilds.isEmpty()) return emptyList()
            if (positions.isEmpty()) return guilds.sortedBy { it.name.lowercase() }
            val byId = guilds.associateBy { it.id }
            val ordered = positions.mapNotNull { byId[it] }
            val placedIds = ordered.map { it.id }.toSet()
            val rest = guilds.filter { it.id !in placedIds }.sortedBy { it.name.lowercase() }
            return ordered + rest
        }
    }
}
