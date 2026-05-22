package dev.puklic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.navigation.RouterState
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
                RouterState.Login -> LoginScreen(viewModel = LoginViewModel(root, root.sessionManager))
                RouterState.Main -> MainScreen(viewModel = MainViewModel(root))
            }
        }
    }
}
