package dev.puklic.platform.ios

import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.TrayClickEvent
import dev.puklic.platform.TrayMenuItem
import dev.puklic.platform.TrayService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * iOS has no system tray. Every mutator throws [PlatformUnavailable]; the
 * `clicks` flow never emits. This is a permanent OS fact.
 */
class IosTrayService : TrayService {
    private val noEvents = MutableSharedFlow<TrayClickEvent>()
    override val clicks: SharedFlow<TrayClickEvent> = noEvents.asSharedFlow()
    override fun setIcon(iconPath: String): Unit = throw PlatformUnavailable("iOS has no system tray")
    override fun setTooltip(text: String): Unit = throw PlatformUnavailable("iOS has no system tray")
    override fun setMenu(items: List<TrayMenuItem>): Unit = throw PlatformUnavailable("iOS has no system tray")
}
