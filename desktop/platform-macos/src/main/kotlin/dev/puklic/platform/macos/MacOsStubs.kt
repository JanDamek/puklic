package dev.puklic.platform.macos

import dev.puklic.platform.PlatformAutoStart
import dev.puklic.platform.PlatformPresence
import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.TrayClickEvent
import dev.puklic.platform.TrayMenuItem
import dev.puklic.platform.TrayService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 1 stubs — Phase 2 brings NSStatusItem (tray), NSWorkspace/IOKit IdleTime (presence),
 * LaunchAgent plist (autostart).
 */

class MacOsTrayService : TrayService {
    private val _clicks = MutableSharedFlow<TrayClickEvent>(extraBufferCapacity = 16)
    override val clicks: SharedFlow<TrayClickEvent> = _clicks.asSharedFlow()
    override fun setIcon(iconPath: String) { /* no-op until Phase 2 */ }
    override fun setTooltip(text: String) { /* no-op until Phase 2 */ }
    override fun setMenu(items: List<TrayMenuItem>) { /* no-op until Phase 2 */ }
}

class MacOsPlatformPresence : PlatformPresence {
    private val _away = MutableStateFlow(false)
    private val _dnd = MutableStateFlow(false)
    override val systemAway: StateFlow<Boolean> = _away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = _dnd.asStateFlow()
}

class MacOsPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false
    override suspend fun isEnabled(): Boolean = false
    override suspend fun setEnabled(enabled: Boolean) {
        throw PlatformUnavailable("AutoStart not yet implemented on macOS (Phase 2)")
    }
}
