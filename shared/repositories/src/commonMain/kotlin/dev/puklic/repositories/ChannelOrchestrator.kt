package dev.puklic.repositories

import co.touchlab.kermit.Logger
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.persistence.repository.ChannelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val CHANNEL_ORCH_TAG = "ChannelOrchestrator"

/**
 * Subscribes to [GatewayEventSource] channel lifecycle events and mirrors them into [storage].
 * Exposes per-guild reactive Flow via [channelsForGuild] **with the visibility filter applied**
 * (issue #18, see architect-report 2026-05-24-channel-permission-design.md §8).
 *
 * The visibility filter uses two flows combined to memoize the expensive permission calculation:
 * the channel list and a `visibleIds: Flow<Set<ChannelId>>` derived from role + self-member +
 * guild-owner state. Only the visibility set is recomputed when role/member data changes;
 * cosmetic channel updates (e.g. `lastMessageId`) skip the calculator entirely.
 *
 * [VisibilityCheck] is implemented on this class as a non-blocking peek into the latest
 * computed `visibleIds`, consumed by [NotificationDispatcher] to suppress cross-cutting events
 * for non-viewable channels.
 */
public class ChannelOrchestrator(
    sessionScope: CoroutineScope,
    private val gatewaySource: GatewayEventSource,
    private val storage: ChannelRepository,
    persistenceContext: CoroutineContext = EmptyCoroutineContext,
    private val roleStore: RoleStore = RoleStore(),
    private val selfMemberStore: SelfMemberStore = SelfMemberStore(),
    /**
     * Provides the owner [UserId] of the given guild, or null if unknown. The orchestrator
     * uses this in the permission calculation to grant the guild owner unconditional visibility.
     * Tests / wiring layer typically pass a lambda that looks up `GuildOrchestrator.guilds`.
     */
    private val guildOwnerProvider: (GuildId) -> UserId? = { null },
) : VisibilityCheck {

    /**
     * Snapshot of currently-visible channel ids per guild. Mutated by the per-guild visibility
     * Flow and read by [isChannelVisible]. Implemented as a [MutableStateFlow] because the
     * mutation happens off the same dispatcher that callers query from.
     */
    private val visibilityIndex: MutableStateFlow<Map<ChannelId, Boolean>> = MutableStateFlow(emptyMap())

    /**
     * Reactive, visibility-filtered channel list for [guildId]. Non-`GuildChannel` entries (DM
     * channels, which Discord never delivers per guild but are defensive-included here) bypass
     * the filter.
     */
    public fun channelsForGuild(guildId: GuildId): Flow<List<Channel>> {
        val everyoneRoleId = Permissions.everyoneRoleId(guildId)
        val visibleIds: Flow<Set<ChannelId>> = combine(
            storage.observeByGuild(guildId),
            roleStore.state,
            selfMemberStore.state,
        ) { channels, allRoles, allMembers ->
            val member = allMembers[guildId]
            val roles = allRoles[guildId].orEmpty()
            val ownerId = guildOwnerProvider(guildId)
            val visible = channels.filterIsInstance<GuildChannel>()
                .filter { ch ->
                    Permissions.canViewSafe(
                        member = member,
                        channel = ch,
                        roles = roles,
                        ownerId = ownerId ?: UserId(0L),
                        everyoneRoleId = everyoneRoleId,
                    )
                }
                .map { it.id }
                .toSet()
            // Update the public peek index for cross-cutting consumers (e.g. notifications).
            updateVisibilityIndex(channels, visible)
            visible
        }.distinctUntilChanged()

        return storage.observeByGuild(guildId).combine(visibleIds) { all, visible ->
            all.filter { it !is GuildChannel || it.id in visible }
        }.onEach { list ->
            Logger.i(CHANNEL_ORCH_TAG) {
                "channel orchestrator: channelsForGuild(${guildId.value}) emit size=${list.size} " +
                    "byType=${list.groupBy { it::class.simpleName }.mapValues { it.value.size }}"
            }
        }
    }

    private fun updateVisibilityIndex(allChannels: List<Channel>, visibleSet: Set<ChannelId>) {
        val current = visibilityIndex.value.toMutableMap()
        for (ch in allChannels) {
            if (ch is GuildChannel) {
                current[ch.id] = ch.id in visibleSet
            }
        }
        visibilityIndex.value = current
    }

    override fun isChannelVisible(channelId: ChannelId): Boolean? = visibilityIndex.value[channelId]

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
                    is GatewayDomainEvent.GuildRolesSnapshot ->
                        roleStore.upsertGuild(event.guildId, event.roles)
                    is GatewayDomainEvent.RoleCreated -> roleStore.upsert(event.role)
                    is GatewayDomainEvent.RoleUpdated -> roleStore.upsert(event.role)
                    is GatewayDomainEvent.RoleDeleted -> roleStore.remove(event.guildId, event.roleId)
                    is GatewayDomainEvent.SelfMemberUpdated -> selfMemberStore.upsertSelf(event.member)
                    is GatewayDomainEvent.GuildDeleted -> {
                        roleStore.removeGuild(event.guildId)
                        selfMemberStore.removeGuild(event.guildId)
                    }
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
