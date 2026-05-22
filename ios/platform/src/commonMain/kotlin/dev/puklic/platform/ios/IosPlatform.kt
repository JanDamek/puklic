package dev.puklic.platform.ios

import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import dev.puklic.platform.Path
import dev.puklic.platform.PlatformAutoStart
import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformPaths
import dev.puklic.platform.PlatformPresence
import dev.puklic.platform.PlatformUnavailable
import dev.puklic.platform.SecureStorage
import dev.puklic.platform.TrayClickEvent
import dev.puklic.platform.TrayMenuItem
import dev.puklic.platform.TrayService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

// Phase 1 stubs for iOS. Phase 2+ will replace with native (Keychain, UNUserNotificationCenter,
// NSFileManager, UIPasteboard, UIApplication, UNUserNotificationCenter, etc.).

private const val PHASE_2 = "iOS platform: Phase 2"

class IosSecureStorage : SecureStorage {
    override suspend fun put(key: String, value: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun get(key: String): String? = throw NotImplementedError(PHASE_2)
    override suspend fun remove(key: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun list(): List<String> = throw NotImplementedError(PHASE_2)
}

class IosNotificationService : NotificationService {
    override suspend fun show(notification: Notification): NotificationHandle =
        throw NotImplementedError(PHASE_2)

    override suspend fun cancel(handle: NotificationHandle): Unit = throw NotImplementedError(PHASE_2)
    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = true, images = true, markup = false)
}

/** iOS has no system tray — every call raises PlatformUnavailable. */
class IosTrayService : TrayService {
    private val noEvents = MutableSharedFlow<TrayClickEvent>()
    override val clicks: SharedFlow<TrayClickEvent> = noEvents.asSharedFlow()
    override fun setIcon(iconPath: String): Unit = throw PlatformUnavailable("iOS has no system tray")
    override fun setTooltip(text: String): Unit = throw PlatformUnavailable("iOS has no system tray")
    override fun setMenu(items: List<TrayMenuItem>): Unit = throw PlatformUnavailable("iOS has no system tray")
}

class IosPlatformPaths : PlatformPaths {
    override val dataDir: Path get() = throw NotImplementedError(PHASE_2)
    override val cacheDir: Path get() = throw NotImplementedError(PHASE_2)
    override val configDir: Path get() = throw NotImplementedError(PHASE_2)
    override val crashDir: Path get() = throw NotImplementedError(PHASE_2)
    override fun databaseFile(): Path = throw NotImplementedError(PHASE_2)
}

class IosPlatformOpen : PlatformOpen {
    override suspend fun openUrl(url: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun openFile(path: Path): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun openInFolder(path: Path): Unit = throw NotImplementedError(PHASE_2)
}

class IosPlatformClipboard : PlatformClipboard {
    override suspend fun setText(text: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun getText(): String? = throw NotImplementedError(PHASE_2)
    override suspend fun setImage(bytes: ByteArray, mimeType: String): Unit = throw NotImplementedError(PHASE_2)
}

/** iOS apps cannot toggle launch-at-boot. */
class IosPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false
    override suspend fun isEnabled(): Boolean = throw NotImplementedError(PHASE_2)
    override suspend fun setEnabled(enabled: Boolean): Unit = throw NotImplementedError(PHASE_2)
}

class IosPlatformPresence : PlatformPresence {
    private val away = MutableStateFlow(false)
    private val dnd = MutableStateFlow(false)
    override val systemAway: StateFlow<Boolean> = away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = dnd.asStateFlow()
}
