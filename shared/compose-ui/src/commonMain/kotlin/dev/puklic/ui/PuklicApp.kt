package dev.puklic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.puklic.persistence.repository.LastPosition
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.navigation.RouterState
import dev.puklic.ui.screens.bootstrap.BootstrappingScreen
import dev.puklic.ui.screens.login.LoginScreen
import dev.puklic.ui.screens.login.LoginViewModel
import dev.puklic.ui.screens.main.MainScreen
import dev.puklic.ui.screens.main.MainViewModel
import dev.puklic.ui.theme.PuklicTheme

/**
 * Top-level Puklic composable. Wraps [PuklicTheme] + routes between [LoginScreen] and
 * [MainScreen] based on [RootComponent.routerState].
 */
@Composable
public fun PuklicApp(root: RootComponent) {
    val routerState by root.routerState.subscribeAsState()
    PuklicTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (routerState) {
                RouterState.Bootstrapping -> BootstrappingScreen()
                RouterState.Login -> LoginScreen(viewModel = LoginViewModel(root, root.sessionManager))
                RouterState.Main -> MainRoute(root)
            }
        }
    }
}

@Composable
private fun MainRoute(root: RootComponent) {
    val activeSession by root.sessionManager.activeSession.collectAsState()
    val initialPosition by produceLastPosition(root.preferences)
    // produceState seeds with null while the (suspend) load runs. We render BootstrappingScreen
    // until the preference fetch completes so MainViewModel can be constructed exactly once
    // with the restored position — recreating the VM on a position change would tear down
    // already-subscribed orchestrator flows.
    val pos = initialPosition ?: run {
        BootstrappingScreen()
        return
    }
    MainScreen(
        viewModel = MainViewModel(
            componentContext = root,
            orchestrators = activeSession?.orchestrators,
            sessionTransport = activeSession?.transport,
            preferences = root.preferences,
            initialPosition = pos,
        ),
    )
}

@Composable
private fun produceLastPosition(prefs: UserPreferencesRepository?) =
    produceState<LastPosition?>(initialValue = null, prefs) {
        value = prefs?.lastPosition() ?: LastPosition.Empty
    }
