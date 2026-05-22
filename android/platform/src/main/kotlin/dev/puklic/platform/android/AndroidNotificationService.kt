package dev.puklic.platform.android

import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService

/** Phase 1 stub. Phase 2 will use NotificationManagerCompat. */
class AndroidNotificationService : NotificationService {
    override suspend fun show(notification: Notification): NotificationHandle =
        throw NotImplementedError("AndroidNotificationService: Phase 2")

    override suspend fun cancel(handle: NotificationHandle): Unit =
        throw NotImplementedError("AndroidNotificationService: Phase 2")

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = true, images = true, markup = false)
}
