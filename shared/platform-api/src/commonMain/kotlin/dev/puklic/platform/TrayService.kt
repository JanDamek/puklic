package dev.puklic.platform

import kotlinx.coroutines.flow.SharedFlow

data class TrayMenuItem(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val separator: Boolean = false,
)

/** Discriminated union of tray icon events. */
sealed interface TrayClickEvent {
    object LeftClick : TrayClickEvent
    object RightClick : TrayClickEvent
    object DoubleClick : TrayClickEvent
    data class MenuItemClick(val itemId: String) : TrayClickEvent
}

/**
 * Desktop tray icon abstraction. Mobile platforms supply a no-op implementation.
 */
interface TrayService {
    fun setIcon(iconPath: String)
    fun setTooltip(text: String)
    fun setMenu(items: kotlin.collections.List<TrayMenuItem>)
    val clicks: SharedFlow<TrayClickEvent>
}
