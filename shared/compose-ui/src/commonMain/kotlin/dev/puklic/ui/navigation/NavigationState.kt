package dev.puklic.ui.navigation

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId

/**
 * Top-level navigation snapshot consumed by the scaffold composables. Mutated only via the
 * [dev.puklic.ui.navigation.RootComponent] events.
 */
public data class NavigationState(
    val selectedGuild: GuildId? = null,
    val selectedChannel: ChannelId? = null,
    val settingsOpen: Boolean = false,
    val commandPaletteOpen: Boolean = false,
)
