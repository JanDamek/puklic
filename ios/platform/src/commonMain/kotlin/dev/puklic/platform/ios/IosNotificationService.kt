package dev.puklic.platform.ios

import dev.puklic.platform.Notification
import dev.puklic.platform.NotificationCapabilities
import dev.puklic.platform.NotificationHandle
import dev.puklic.platform.NotificationService
import dev.puklic.platform.PlatformDenied
import dev.puklic.platform.PlatformFailed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS local notifications via `UNUserNotificationCenter`.
 *
 * Capabilities: actions and image attachments are NOT advertised in v1 because
 * they require category registration + file-URL attachments, which are not yet
 * wired. Marking them as supported would be a lie (HARD RULE #2). When those
 * pipelines land they will be added to [NotificationCapabilities] honestly.
 */
@OptIn(ExperimentalForeignApi::class)
class IosNotificationService(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
) : NotificationService {

    override val supported: NotificationCapabilities =
        NotificationCapabilities(actions = false, images = false, markup = false)

    override suspend fun show(notification: Notification): NotificationHandle {
        ensureAuthorised()
        val id = NSUUID.UUID().UUIDString
        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            timeInterval = 0.1,
            repeats = false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = id,
            content = content,
            trigger = trigger,
        )
        suspendCancellableCoroutine<Unit> { cont ->
            center.addNotificationRequest(request) { error: NSError? ->
                if (error == null) cont.resume(Unit)
                else cont.resumeWithException(PlatformFailed("addNotificationRequest failed: ${error.localizedDescription}"))
            }
        }
        return NotificationHandle(id)
    }

    override suspend fun cancel(handle: NotificationHandle) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(handle.id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(handle.id))
    }

    private suspend fun ensureAuthorised() {
        val granted = suspendCancellableCoroutine<Boolean> { cont ->
            center.requestAuthorizationWithOptions(
                options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
            ) { granted, error ->
                if (error != null) cont.resumeWithException(PlatformFailed("requestAuthorization failed: ${error.localizedDescription}"))
                else cont.resume(granted)
            }
        }
        if (!granted) throw PlatformDenied("User declined notification authorisation")
    }
}
