package dev.puklic.ui.screens.main

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.domain.Channel
import dev.puklic.domain.Guild
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot of the main screen's guild + channel state. Phase 1 uses an empty placeholder —
 * step 15-16 will wire this to the live `GuildRepository` / `ChannelRepository` reactive Flows.
 */
public data class MainScreenState(
    val guilds: List<Guild> = emptyList(),
    val channelsByGuild: Map<GuildId, List<Channel>> = emptyMap(),
    val selectedGuildId: GuildId? = null,
    val selectedChannelId: ChannelId? = null,
)

/**
 * Decompose-style ViewModel for the three-pane authenticated UI. Phase 1 only carries a
 * placeholder state; data wiring lands in step 15-16.
 */
public class MainViewModel(
    componentContext: ComponentContext,
    externalScope: CoroutineScope? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = externalScope ?: lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(MainScreenState())
    public val state: StateFlow<MainScreenState> = _state.asStateFlow()

    public fun selectGuild(id: GuildId) {
        _state.value = _state.value.copy(selectedGuildId = id, selectedChannelId = null)
    }

    public fun selectChannel(id: ChannelId) {
        _state.value = _state.value.copy(selectedChannelId = id)
    }
}
