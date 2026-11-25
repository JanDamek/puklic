package dev.puklic.platform.android

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.puklic.android.platform.R
import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformDenied
import dev.puklic.platform.PlatformFailed
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local notifications through `NotificationManagerCompat`. Urgent notifications go to the high
 * importance mentions channel, the rest to the messages channel. `POST_NOTIFICATIONS` (Android 13+)
 * is requested the first time a notification is shown. A notification that carries a tag replaces the
 * previous one with the same tag; tapping any notification opens the app.
 *
 * Actions, images and markup are not advertised: the service has no way to deliver an action tap back
 * to a caller and the notification small icon is a fixed app resource.
 */
class AndroidNotificationService(
    private val context: Context,
    private val permissions: RuntimePermissions,
) : NotificationService {
    private val manager = NotificationManagerCompat.from(context)
    private val nextId = AtomicInteger(FIRST_UNTAGGED_ID)

    init {
        context.registerNotificationChannels()
    }

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = false)

    @SuppressLint("MissingPermission")
    override suspend fun show(notification: Notification): NotificationHandle {
        if (!permissions.ensureGranted(RuntimePermissions.NOTIFICATIONS)) {
            throw PlatformDenied("User declined the notification permission")
        }
        val id = notification.tag?.hashCode() ?: nextId.incrementAndGet()
        manager.notify(id, build(notification))
        return NotificationHandle(id.toString())
    }

    override suspend fun cancel(handle: NotificationHandle) {
        val id = handle.id.toIntOrNull() ?: throw PlatformFailed("Unknown notification handle: ${handle.id}")
        manager.cancel(id)
    }

    private fun build(notification: Notification): android.app.Notification {
        val channel = if (notification.urgent) AppNotificationChannel.Mentions else AppNotificationChannel.Messages
        return NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_puklic)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(context.launchAppPendingIntent())
            .setAutoCancel(true)
            .build()
    }

    private companion object {
        const val FIRST_UNTAGGED_ID = 1_000
    }
}
