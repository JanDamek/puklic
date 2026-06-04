package dev.puklic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import dev.puklic.persistence.repository.LastPosition
import dev.puklic.persistence.repository.LastPositionCodec
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.SecureStorage
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.navigation.RouterState
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.LocalEmojiResolver
import dev.puklic.ui.resolvers.LocalMentionResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.NoopEmojiResolver
import dev.puklic.ui.resolvers.NoopMentionResolver
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
public fun PuklicApp(
    root: RootComponent,
    mentionResolver: MentionResolver = NoopMentionResolver,
    emojiResolver: EmojiResolver = NoopEmojiResolver,
    platformOpen: PlatformOpen? = null,
) {
    val routerState by root.routerState.subscribeAsState()
    PuklicTheme {
        CompositionLocalProvider(
            LocalMentionResolver provides mentionResolver,
            LocalEmojiResolver provides emojiResolver,
        ) {
            // Full-bleed background; content is inset to the platform safe area so
            // interactive elements (the compact-layout drawer hamburger, headers) never sit
            // under the iOS status bar / Dynamic Island or the top-left "back to previous app"
            // zone. On desktop the safe-drawing insets are zero, so this is a no-op there.
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    when (routerState) {
                        RouterState.Bootstrapping -> BootstrappingScreen()
                        RouterState.Login -> LoginScreen(
                            viewModel = LoginViewModel(
                                componentContext = root,
                                sessionManager = root.sessionManager,
                                secureStorage = root.secureStorage,
                            ),
                        )
                        RouterState.Main -> MainRoute(root, platformOpen)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainRoute(root: RootComponent, platformOpen: PlatformOpen?) {
    val activeSession by root.sessionManager.activeSession.collectAsState()
    val initialPosition by produceLastPosition(root.preferences, root.secureStorage)
    // produceState seeds with null while the (suspend) load runs. We render BootstrappingScreen
    // until the preference fetch completes so MainViewModel can be constructed exactly once
    // with the restored position — recreating the VM on a position change would tear down
    // already-subscribed orchestrator flows.
    val pos = initialPosition ?: run {
        BootstrappingScreen()
        return
    }
    val viewModel = remember(activeSession, pos) {
        MainViewModel(
            componentContext = root,
            orchestrators = activeSession?.orchestrators,
            sessionTransport = activeSession?.transport,
            preferences = root.preferences,
            secureStorage = root.secureStorage,
            initialPosition = pos,
            voiceClient = activeSession?.voiceClient,
            sessionManager = root.sessionManager,
            dmCreator = activeSession?.dmCreator,
            friendInviter = activeSession?.friendInviter,
            serverJoiner = activeSession?.serverJoiner,
        )
    }
    val uriHandler = LocalUriHandler.current
    val appRouter = remember(viewModel, uriHandler) {
        AppRouter(
            openChannel = { viewModel.selectChannel(it) },
            openExternal = { uriHandler.openUri(it) },
        )
    }
    CompositionLocalProvider(LocalAppRouter provides appRouter) {
        MainScreen(viewModel = viewModel, platformOpen = platformOpen)
    }
}

@Composable
private fun produceLastPosition(
    prefs: UserPreferencesRepository?,
    secureStorage: SecureStorage?,
) = produceState<LastPosition?>(initialValue = null, prefs, secureStorage) {
    val synced = LastPositionCodec.decode(secureStorage?.get(MainViewModel.LAST_POSITION_KEY))
    value = if (synced != LastPosition.Empty) synced else prefs?.lastPosition() ?: LastPosition.Empty
}
