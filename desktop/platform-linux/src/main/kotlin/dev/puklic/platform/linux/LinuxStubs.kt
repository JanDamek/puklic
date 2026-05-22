package dev.puklic.platform.linux

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
 * Phase 1 stubs — real implementations land in Phase 2 (Tray = StatusNotifierItem,
 * Presence = org.gnome.SessionManager IdleMonitor, AutoStart = .desktop file under
 * `~/.config/autostart/`).
 */

class LinuxTrayService : TrayService {
    private val _clicks = MutableSharedFlow<TrayClickEvent>(extraBufferCapacity = 16)
    override val clicks: SharedFlow<TrayClickEvent> = _clicks.asSharedFlow()
    override fun setIcon(iconPath: String) { /* no-op until Phase 2 */ }
    override fun setTooltip(text: String) { /* no-op until Phase 2 */ }
    override fun setMenu(items: List<TrayMenuItem>) { /* no-op until Phase 2 */ }
}

class LinuxPlatformPresence : PlatformPresence {
    private val _away = MutableStateFlow(false)
    private val _dnd = MutableStateFlow(false)
    override val systemAway: StateFlow<Boolean> = _away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = _dnd.asStateFlow()
}

class LinuxPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false
    override suspend fun isEnabled(): Boolean = false
    override suspend fun setEnabled(enabled: Boolean) {
        throw PlatformUnavailable("AutoStart not yet implemented on Linux (Phase 2)")
    }
}
