package dev.puklic.ios

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import dev.puklic.persistence.repository.UserPreferencesRepository
import dev.puklic.platform.PlatformOpen
import dev.puklic.session.SessionManager
import dev.puklic.ui.PuklicApp
import dev.puklic.ui.navigation.RootComponent
import dev.puklic.ui.resolvers.EmojiResolver
import dev.puklic.ui.resolvers.MentionResolver
import dev.puklic.ui.resolvers.NoopEmojiResolver
import dev.puklic.ui.resolvers.NoopMentionResolver
import platform.UIKit.UIViewController

/**
 * Swift-callable entry point: returns a `UIViewController` hosting the Puklic Compose UI.
 *
 * The iOS app (`iosApp.xcodeproj`) constructs the dependency graph via `IosDependencyGraph`
 * (see Slice 3.5 follow-up issue) and passes the resulting `SessionManager` (+ optional
 * preferences and resolvers) into this factory. The factory owns the Decompose lifecycle
 * for the `RootComponent` it builds.
 *
 * Bundle ID for distribution: `cz.damek.puklic.app` (App Store / TestFlight only — see
 * `docs/03_infrastructure/architect-reports/2026-05-28-apple-distribution.md`).
 */
public fun puklicAppRootViewController(
    sessionManager: SessionManager,
    preferences: UserPreferencesRepository? = null,
    mentionResolver: MentionResolver = NoopMentionResolver,
    emojiResolver: EmojiResolver = NoopEmojiResolver,
    platformOpen: PlatformOpen? = null,
): UIViewController {
    val lifecycle = LifecycleRegistry()
    val componentContext = DefaultComponentContext(lifecycle = lifecycle)
    val root = RootComponent(
        componentContext = componentContext,
        sessionManager = sessionManager,
        preferences = preferences,
    )
    lifecycle.resume()
    return ComposeUIViewController {
        PuklicApp(
            root = root,
            mentionResolver = mentionResolver,
            emojiResolver = emojiResolver,
            platformOpen = platformOpen,
        )
    }
}
