package dev.puklic.platform.android

import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.TrayClickEvent
import dev.puklic.platform.TrayMenuItem
import dev.puklic.platform.TrayService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Android has no system tray — every operation raises [PlatformUnavailable].
 * The clicks flow is exposed but will never emit.
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
