package dev.puklic.platform.macos

import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformUnavailable

/**
 * macOS Notifications via `osascript -e 'display notification ...'`. MVP only — no actions,
 * no images, no reply UI. Phase 2 switches to UNUserNotificationCenter via JNA.
 */
class MacOsNotificationService(
    private val runner: CommandRunner = CommandRunner(),
) : NotificationService {

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = false)

    override suspend fun show(notification: Notification): NotificationHandle {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("osascript not found at $EXEC")
        }
        val script = buildScript(notification)
        runner.run(args = listOf(EXEC, "-e", script)).successOrThrow("osascript")
        return NotificationHandle(id = notification.tag ?: notification.title)
    }

    override suspend fun cancel(handle: NotificationHandle) {
        // osascript cannot cancel a posted notification. No-op until Phase 2.
    }

    companion object {
        internal const val EXEC = "/usr/bin/osascript"

        internal fun buildScript(n: Notification): String {
            val title = escape(n.title)
            val body = escape(n.body)
            val subtitle = escape("Puklic")
            return """display notification "$body" with title "$title" subtitle "$subtitle""""
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
