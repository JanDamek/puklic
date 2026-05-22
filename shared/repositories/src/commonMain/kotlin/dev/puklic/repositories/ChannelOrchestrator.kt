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
import kotlinx.coroutines.launch

private const val CHANNEL_ORCH_TAG = "ChannelOrchestrator"

/**
 * Subscribes to [GatewayEventSource] channel lifecycle events and mirrors them into [storage].
 * Exposes per-guild reactive Flow via [channelsForGuild].
 */
public class ChannelOrchestrator(
    private val sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: ChannelRepository,
) {
    /** Reactive channel list for [guildId], backed by SQLite. */
    public fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> =
        kotlinx.coroutines.flow.flow {
            storage.observeByGuild(guildId).collect { list ->
                Logger.i(CHANNEL_ORCH_TAG) {
                    "channel orchestrator: observeByGuild(${guildId.value}) emit size=${list.size} " +
                        "byType=${list.groupBy { it::class.simpleName }.mapValues { it.value.size }}"
                }
                emit(list)
            }
        }

    // Diagnostic counters — incremented for every observed ChannelCreated, regardless of type.
    // Reset is not implemented (READY brings a single burst; subsequent CHANNEL_CREATE events trickle).
    private var totalSeen: Int = 0
    private var totalPersisted: Int = 0
    private val perTypeSeen: MutableMap<String, Int> = mutableMapOf()
    private val perGuildPersisted: MutableMap<Long, Int> = mutableMapOf()

    init {
        sessionScope.launch {
            gatewaySource.events.collect { event ->
                when (event) {
                    is GatewayDomainEvent.ChannelCreated -> handleChannelArrival(event.channel, isCreate = true)
                    is GatewayDomainEvent.ChannelUpdated -> handleChannelArrival(event.channel, isCreate = false)
                    is GatewayDomainEvent.ChannelDeleted -> storage.delete(event.channelId)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleChannelArrival(channel: Channel, isCreate: Boolean) {
        totalSeen += 1
        val kind = channel::class.simpleName ?: "Unknown"
        perTypeSeen[kind] = (perTypeSeen[kind] ?: 0) + 1
        // Only guild-scoped channels go through ChannelRepository; DM channels are not part of
        // the per-guild list and are handled separately by DmListOrchestrator.
        if (channel is GuildTextChannel || channel is GuildCategoryChannel) {
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
            // DM and unsupported types — ignored here but counted for visibility.
            val isDm = channel is DmChannel
            Logger.i(CHANNEL_ORCH_TAG) {
                "channel orchestrator: skipping non-guild channel kind=$kind isDm=$isDm seen=$totalSeen"
            }
        }
    }
}
