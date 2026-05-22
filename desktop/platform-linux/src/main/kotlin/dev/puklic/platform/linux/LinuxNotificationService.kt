package dev.puklic.platform.linux

import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformUnavailable

/**
 * `notify-send` (libnotify) backed notifications. Phase 1: no actions, no images.
 * Phase 2: switch to direct D-Bus for action callbacks.
 */
class LinuxNotificationService(
    private val runner: CommandRunner = CommandRunner(),
) : NotificationService {

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = true)

    override suspend fun show(notification: Notification): NotificationHandle {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("libnotify not installed (missing `$EXEC`)")
        }
        val args = buildArgs(notification)
        runner.run(args).successOrThrow("notify-send")
        return NotificationHandle(id = notification.tag ?: notification.title)
    }

    override suspend fun cancel(handle: NotificationHandle) {
        // notify-send does not expose cancellation via CLI in a portable way.
        // No-op for Phase 1; Phase 2 D-Bus impl will support CloseNotification.
    }

    companion object {
        internal const val EXEC = "notify-send"

        internal fun buildArgs(n: Notification): List<String> = buildList {
            add(EXEC)
            add("--app-name=Puklic")
            add("--urgency=${if (n.urgent) "critical" else "normal"}")
            n.iconPath?.let { add("--icon=$it") }
            add(n.title)
            add(n.body)
        }
    }
}
