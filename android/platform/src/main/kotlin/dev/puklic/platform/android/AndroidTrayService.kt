package dev.puklic.platform.android

import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.TrayClickEvent
import dev.puklic.platform.TrayMenuItem
import dev.puklic.platform.TrayService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android has no system tray, a permanent platform fact. Every mutator raises [PlatformUnavailable]
 * and [clicks] never emits, matching the iOS implementation. The Android app does not wire a
 * [TrayService] into its dependency graph because no shared code consumes one.
 */
class AndroidTrayService : TrayService {
    private val noEvents = MutableSharedFlow<TrayClickEvent>()
    override val clicks: SharedFlow<TrayClickEvent> = noEvents.asSharedFlow()

    override fun setIcon(iconPath: String): Unit = throw PlatformUnavailable(NO_TRAY)
    override fun setTooltip(text: String): Unit = throw PlatformUnavailable(NO_TRAY)
    override fun setMenu(items: List<TrayMenuItem>): Unit = throw PlatformUnavailable(NO_TRAY)

    private companion object {
        const val NO_TRAY = "Android has no system tray"
    }
}
