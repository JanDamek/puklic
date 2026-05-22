package dev.puklic.platform

/** Opaque handle returned by [NotificationService.show] and accepted by [NotificationService.cancel]. */
data class NotificationHandle(val id: String)

data class NotificationAction(
    val id: String,
    val label: String,
)

data class NotificationCapabilities(
    val actions: Boolean,
    val images: Boolean,
    val markup: Boolean,
)

data class Notification(
    val title: String,
    val body: String,
    val iconPath: String?,
    val actions: kotlin.collections.List<NotificationAction>,
    val tag: String?,
    val urgent: Boolean,
)

interface NotificationService {
    suspend fun show(notification: Notification): NotificationHandle
    suspend fun cancel(handle: NotificationHandle)
    val supported: NotificationCapabilities
}
