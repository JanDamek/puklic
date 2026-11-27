package dev.puklic.platform

import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId

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

/** Where a tap on a notification leads: [channelId] inside [guildId], or a DM channel when [guildId] is null. */
data class NotificationTarget(
    val guildId: GuildId?,
    val channelId: ChannelId,
)

data class Notification(
    val title: String,
    val body: String,
    val iconPath: String?,
    val actions: kotlin.collections.List<NotificationAction>,
    val tag: String?,
    val urgent: Boolean,
    val target: NotificationTarget? = null,
)

interface NotificationService {
    suspend fun show(notification: Notification): NotificationHandle
    suspend fun cancel(handle: NotificationHandle)
    val supported: NotificationCapabilities
}
