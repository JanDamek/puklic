package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.GuildId
import dev.puklic.persistence.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val CHANNEL_ORCH_TAG = "ChannelOrchestrator"

/**
 * Subscribes to [GatewayEventSource] channel lifecycle events and mirrors them into [storage].
 * Exposes per-guild reactive Flow via [channelsForGuild].
 *
 * Implementation is fully Flow-first: declarative operator chain (filterIsInstance → onEach →
 * catch → flowOn → launchIn). The `catch` operator ensures that a single persistence error
 * logs and the upstream Flow keeps emitting — no inner try/catch, no SupervisorJob gymnastics,
 * no `tryEmit` drops.
 */
public class ChannelOrchestrator(
    sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: ChannelRepository,
    persistenceContext: CoroutineContext = EmptyCoroutineContext,
) {
    /** Reactive channel list for [guildId], backed by SQLite. */
    public fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> =
        storage.observeByGuild(guildId)
            .onEach { list ->
                Logger.i(CHANNEL_ORCH_TAG) {
                    "channel orchestrator: observeByGuild(${guildId.value}) emit size=${list.size} " +
                        "byType=${list.groupBy { it::class.simpleName }.mapValues { it.value.size }}"
                }
            }

    // Diagnostic counters — single-threaded by virtue of running on the same Flow collector.
    private var totalSeen: Int = 0
    private var totalPersisted: Int = 0
    private val perTypeSeen: MutableMap<String, Int> = mutableMapOf()
    private val perGuildPersisted: MutableMap<Long, Int> = mutableMapOf()

    init {
        gatewaySource.events
            .filterIsInstance<GatewayDomainEvent>()
            .onEach { event ->
                when (event) {
                    is GatewayDomainEvent.ChannelCreated -> handleArrival(event.channel, isCreate = true)
                    is GatewayDomainEvent.ChannelUpdated -> handleArrival(event.channel, isCreate = false)
                    is GatewayDomainEvent.ChannelDeleted -> storage.delete(event.channelId)
                    else -> Unit
                }
            }
            .catch { t ->
                Logger.w(CHANNEL_ORCH_TAG) {
                    "channel orchestrator: flow error caught, will not propagate " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
            .flowOn(persistenceContext)
            .launchIn(sessionScope)
    }

    private suspend fun handleArrival(channel: Channel, isCreate: Boolean) {
        totalSeen += 1
        val kind = channel::class.simpleName ?: "Unknown"
        perTypeSeen[kind] = (perTypeSeen[kind] ?: 0) + 1
        if (channel is GuildTextChannel || channel is GuildCategoryChannel || channel is DmChannel) {
            try {
                storage.persist(channel)
                totalPersisted += 1
                val gid = when (channel) {
                    is GuildTextChannel -> channel.guildId.value
                    is GuildCategoryChannel -> channel.guildId.value
                    else -> 0L
                }
                perGuildPersisted[gid] = (perGuildPersisted[gid] ?: 0) + 1
                if (totalSeen % 25 == 0 || totalSeen <= 5) {
                    Logger.i(CHANNEL_ORCH_TAG) {
                        "channel orchestrator: persisted total=$totalPersisted seen=$totalSeen " +
                            "byType=$perTypeSeen byGuild=$perGuildPersisted lastKind=$kind isCreate=$isCreate"
                    }
                }
            } catch (t: Throwable) {
                Logger.w(CHANNEL_ORCH_TAG) {
                    "channel orchestrator: persist FAILED kind=$kind " +
                        "cause=${t::class.simpleName} msg=${t.message?.take(200)}"
                }
            }
        } else {
            Logger.i(CHANNEL_ORCH_TAG) {
                "channel orchestrator: skipping non-persistable channel kind=$kind seen=$totalSeen"
            }
        }
    }
}
