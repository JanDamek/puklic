package dev.puklic.ui.screens.main

import co.touchlab.kermit.Logger
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.repositories.Orchestrators
import dev.puklic.session.SessionTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Snapshot of the main screen's guild + channel state. Backed by the live orchestrators
 * (when available); falls back to empty placeholder when no session is active (e.g. tests).
 */
/**
 * Top-level navigation scope. The user is either viewing a guild (selectedGuildId set) or the
 * DM "Home" landing (dmHomeSelected = true). Mutually exclusive; default is [Empty].
 */
public sealed interface NavigationScope {
    public data object Empty : NavigationScope
    public data class GuildSelected(val id: GuildId) : NavigationScope
    public data object DmHome : NavigationScope
}

public data class MainScreenState(
    val guilds: List<Guild> = emptyList(),
    val channelsForSelectedGuild: List<Channel> = emptyList(),
    val dmChannels: List<DmChannel> = emptyList(),
    val scope: NavigationScope = NavigationScope.Empty,
    val selectedChannelId: ChannelId? = null,
) {
    val selectedGuildId: GuildId? get() = (scope as? NavigationScope.GuildSelected)?.id
    val isDmHome: Boolean get() = scope is NavigationScope.DmHome
}

/**
 * Decompose-style ViewModel for the three-pane authenticated UI. When [orchestrators] is non-null,
 * [state] reflects the live guild + channel reactive Flows; selection is local to this view-model.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public class MainViewModel(
    componentContext: ComponentContext,
    public val orchestrators: Orchestrators? = null,
    public val sessionTransport: SessionTransport? = null,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val navScope = MutableStateFlow<NavigationScope>(NavigationScope.Empty)
    private val selectedGuild = MutableStateFlow<GuildId?>(null)
    private val selectedChannel = MutableStateFlow<ChannelId?>(null)

    public val state: StateFlow<MainScreenState> = if (orchestrators == null) {
        MutableStateFlow(MainScreenState()).asStateFlow()
    } else {
        val guilds = orchestrators.guild.guilds
            .onEach { Logger.i("MainViewModel") { "viewmodel: guilds state changed, size=${it.size}" } }
        val channelFlow = selectedGuild.flatMapLatest { gid ->
            if (gid == null) flowOf(emptyList()) else orchestrators.channel.channelsForGuild(gid)
        }
        val dmFlow = orchestrators.dms.dms
        combine(guilds, channelFlow, dmFlow, navScope, selectedChannel) { gs, chs, dms, sc, cid ->
            MainScreenState(
                guilds = gs,
                channelsForSelectedGuild = chs,
                dmChannels = dms,
                scope = sc,
                selectedChannelId = cid,
            )
        }.stateIn(scope, SharingStarted.Eagerly, MainScreenState())
    }

    /** Switch to the DM "Home" view — clears guild selection. */
    public fun selectDmHome() {
        navScope.value = NavigationScope.DmHome
        selectedGuild.value = null
        selectedChannel.value = null
    }

    public fun selectGuild(id: GuildId) {
        navScope.value = NavigationScope.GuildSelected(id)
        selectedGuild.value = id
        selectedChannel.value = null
        // Lazy-subscribe to the first text channels of this guild. Required by Discord user-mode
        // so REST `getMessages` stops returning 50001 on member-list-gated channels and so
        // GUILD_MEMBER_LIST_UPDATE events flow.
        val transport = sessionTransport ?: return
        val orch = orchestrators ?: return
        scope.launch {
            val channels = orch.channel.channelsForGuild(id).first()
            val textChannelIds = channels
                .filterIsInstance<GuildTextChannel>()
                .sortedBy { it.position }
                .take(LAZY_SUBSCRIBE_BOOTSTRAP)
                .map { it.id }
            if (textChannelIds.isNotEmpty()) {
                transport.lazyRequestGuild(id, textChannelIds)
            }
        }
    }

    public fun selectChannel(id: ChannelId) {
        selectedChannel.value = id
        val transport = sessionTransport ?: return
        val gid = selectedGuild.value ?: return
        // Re-subscribe with the focused channel so Discord lifts the 50001 gate before the next
        // REST `getMessages` issued by the messages orchestrator. A short settle delay gives
        // Discord time to register the subscription internally before REST runs — without it,
        // the gate may still be active and the first call returns 50001.
        scope.launch {
            transport.lazyRequestGuild(gid, listOf(id))
            delay(LAZY_SUBSCRIBE_SETTLE_MS)
        }
    }

    private companion object {
        const val LAZY_SUBSCRIBE_BOOTSTRAP = 5
        const val LAZY_SUBSCRIBE_SETTLE_MS = 500L
    }
}
