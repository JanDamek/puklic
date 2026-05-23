package dev.puklic.ui.navigation

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.coroutines.coroutineScope as lifecycleCoroutineScope
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.session.BootstrapPhase
import dev.puklic.session.DiscordSession
import dev.puklic.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Root navigation component per ADR-0005. Holds the [NavigationState] and exposes a
 * derived [routerState] that the scaffold composables observe to decide whether to show
 * [LoginScreen]-equivalent or the main authenticated UI.
 *
 * The component lifecycle is owned by Decompose; the coroutine scope is bound to it via
 * `lifecycle.coroutineScope()`, so cancelling the component cancels in-flight work.
 */
public class RootComponent(
    componentContext: ComponentContext,
    public val sessionManager: SessionManager,
    public val preferences: UserPreferencesRepository? = null,
) : ComponentContext by componentContext {

    public val scope: CoroutineScope = lifecycleCoroutineScope(Dispatchers.Main.immediate)

    private val navState = MutableStateFlow(NavigationState())
    public val navigation: StateFlow<NavigationState> = navState.asStateFlow()

    private val _routerState: MutableValue<RouterState> = MutableValue(
        deriveRoute(sessionManager.activeSession.value, sessionManager.bootstrap.value),
    )
    public val routerState: Value<RouterState> = _routerState

    init {
        scope.launch {
            combine(sessionManager.activeSession, sessionManager.bootstrap) { session, phase ->
                deriveRoute(session, phase)
            }.collect { state -> _routerState.value = state }
        }
    }

    public fun selectGuild(id: GuildId) {
        navState.update { it.copy(selectedGuild = id, selectedChannel = null) }
    }

    public fun selectChannel(id: ChannelId) {
        navState.update { it.copy(selectedChannel = id) }
    }

    public fun openSettings() {
        navState.update { it.copy(settingsOpen = true) }
    }

    public fun closeSettings() {
        navState.update { it.copy(settingsOpen = false) }
    }

    public fun toggleCommandPalette() {
        navState.update { it.copy(commandPaletteOpen = !it.commandPaletteOpen) }
    }

    private fun deriveRoute(session: DiscordSession?, phase: BootstrapPhase): RouterState = when {
        session != null -> RouterState.Main
        phase == BootstrapPhase.Loading -> RouterState.Bootstrapping
        else -> RouterState.Login
    }
}

/**
 * Top-level routing decision.
 *  - [Bootstrapping]: app just started and a stored token is being validated.
 *  - [Login]: no stored token, or stored token rejected (401) — show the login form.
 *  - [Main]: an authenticated session is active.
 */
public sealed interface RouterState {
    public data object Bootstrapping : RouterState
    public data object Login : RouterState
    public data object Main : RouterState
}
