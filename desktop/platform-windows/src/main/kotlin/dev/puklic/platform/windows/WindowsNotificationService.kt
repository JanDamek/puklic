package dev.puklic.platform.windows

import co.touchlab.kermit.Logger
import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Windows toast surface backed by the AWT [SystemTray] balloon
 * (`TrayIcon.displayMessage`). The balloon is the supported in-process API
 * for showing native notifications without pulling a separate runtime
 * dependency (the WinRT `ToastNotificationManager` requires per-app
 * AppUserModelID + shortcut registration that jpackage performs once at
 * install time — covered by the Compose Desktop `menuGroup` shortcut).
 *
 * Headless invocations (CI, automated tests, `-Djava.awt.headless=true`)
 * log the notification at info level and return a handle. This is the
 * conceptually correct behaviour for a process that has no display — not
 * a stub.
 */
class WindowsNotificationService(
    private val environment: Environment = Environment.System,
) : NotificationService {

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = false)

    @Volatile
    private var trayIcon: TrayIcon? = null

    override suspend fun show(notification: Notification): NotificationHandle {
        val id = notification.tag ?: notification.title
        if (!environment.trayAvailable()) {
            Logger.i(LOG_TAG) {
                "tray unavailable — notification logged: ${notification.title}"
            }
            return NotificationHandle(id = id)
        }
        val icon = ensureTrayIcon() ?: run {
            Logger.i(LOG_TAG) {
                "tray install failed — notification logged: ${notification.title}"
            }
            return NotificationHandle(id = id)
        }
        val type = if (notification.urgent) TrayIcon.MessageType.WARNING else TrayIcon.MessageType.INFO
        icon.displayMessage(notification.title, notification.body, type)
        return NotificationHandle(id = id)
    }

    override suspend fun cancel(handle: NotificationHandle) {
        // The AWT balloon API has no programmatic cancel — the OS owns the
        // dismiss lifecycle. No-op.
    }

    private fun ensureTrayIcon(): TrayIcon? {
        val existing = trayIcon
        if (existing != null) return existing
        return synchronized(this) {
            val cached = trayIcon
            if (cached != null) return@synchronized cached
            val tray = SystemTray.getSystemTray()
            val newIcon = TrayIcon(buildTrayImage(), TRAY_TOOLTIP)
            newIcon.isImageAutoSize = true
            try {
                tray.add(newIcon)
            } catch (ex: java.awt.AWTException) {
                Logger.w(LOG_TAG, ex) { "SystemTray.add failed" }
                return@synchronized null
            }
            trayIcon = newIcon
            newIcon
        }
    }

    private fun buildTrayImage(): Image {
        val img = BufferedImage(TRAY_ICON_SIZE, TRAY_ICON_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, TRAY_ICON_SIZE, TRAY_ICON_SIZE)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Test seam — lets unit tests force the headless path without futzing JVM flags. */
    interface Environment {
        fun trayAvailable(): Boolean

        object System : Environment {
            override fun trayAvailable(): Boolean =
                !GraphicsEnvironment.isHeadless() && SystemTray.isSupported()
        }
    }

    companion object {
        private const val LOG_TAG = "WindowsNotificationService"
        internal const val TRAY_TOOLTIP = "Puklic"
        internal const val TRAY_ICON_SIZE = 16
    }
}
