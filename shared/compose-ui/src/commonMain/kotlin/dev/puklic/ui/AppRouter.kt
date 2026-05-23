package dev.puklic.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.puklic.ids.ChannelId
import dev.puklic.repositories.DiscordRoute
import dev.puklic.repositories.DiscordUrlRouter

/**
 * Routes URL clicks coming from rendered messages to either an in-app destination
 * (e.g. switching the active channel for a `discord.com/channels/...` link) or to
 * the platform browser via [openExternal] for everything else.
 *
 * Issue #7: previously every link was forwarded to `LocalUriHandler` which opened
 * the system browser even for Discord channel links.
 */
public class AppRouter(
    private val openChannel: (ChannelId) -> Unit,
    private val openExternal: (String) -> Unit,
) {
    public fun open(url: String) {
        when (val route = DiscordUrlRouter.parse(url)) {
            is DiscordRoute.Channel -> openChannel(route.channelId)
            is DiscordRoute.Dm -> openChannel(route.channelId)
            is DiscordRoute.User -> openExternal(url) // v1: no in-app profile popup yet
            is DiscordRoute.External -> openExternal(route.url)
        }
    }
}

/**
 * Composition local providing the active [AppRouter]. Defaults to a no-op router
 * that swallows clicks, so previews / tests that don't wire the real one don't crash.
 */
public val LocalAppRouter: androidx.compose.runtime.ProvidableCompositionLocal<AppRouter> =
    staticCompositionLocalOf {
        AppRouter(openChannel = {}, openExternal = {})
    }
