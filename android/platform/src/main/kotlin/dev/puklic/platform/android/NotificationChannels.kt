package dev.puklic.platform.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import dev.puklic.android.platform.R
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.platform.NotificationTarget

/** The notification channels Puklic posts to. Names are localized string resources. */
internal enum class AppNotificationChannel(
    val id: String,
    @StringRes val nameRes: Int,
    val importance: Int,
) {
    Messages("puklic.messages", R.string.channel_messages, NotificationManagerCompat.IMPORTANCE_DEFAULT),
    Mentions("puklic.mentions", R.string.channel_mentions, NotificationManagerCompat.IMPORTANCE_HIGH),
    Voice("puklic.voice", R.string.channel_voice, NotificationManagerCompat.IMPORTANCE_LOW),
    Connection("puklic.connection", R.string.channel_connection, NotificationManagerCompat.IMPORTANCE_LOW),
}

/** Registers every [AppNotificationChannel]; the platform ignores channels that already exist. */
internal fun Context.registerNotificationChannels() {
    val manager = NotificationManagerCompat.from(this)
    AppNotificationChannel.entries.forEach { channel ->
        val compat = NotificationChannelCompat.Builder(channel.id, channel.importance)
            .setName(getString(channel.nameRes))
            .build()
        manager.createNotificationChannel(compat)
    }
}

/** Tapping a notification brings the app's launcher activity to the foreground. */
internal fun Context.launchAppPendingIntent(): PendingIntent {
    val launchIntent = checkNotNull(packageManager.getLaunchIntentForPackage(packageName)) {
        "No launcher activity for $packageName"
    }
    return PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/**
 * Id of the single ongoing foreground notification. The voice and the background connection services
 * share it, so a voice call replaces the connection notification instead of adding a second one.
 */
internal const val FOREGROUND_NOTIFICATION_ID = 2_001

private const val EXTRA_TARGET_CHANNEL = "dev.puklic.extra.TARGET_CHANNEL"
private const val EXTRA_TARGET_GUILD = "dev.puklic.extra.TARGET_GUILD"
private const val NO_ID = 0L

/** Tapping a message notification opens the launcher activity on the channel it came from. */
internal fun Context.channelPendingIntent(target: NotificationTarget): PendingIntent {
    val intent = checkNotNull(packageManager.getLaunchIntentForPackage(packageName)) {
        "No launcher activity for $packageName"
    }.apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        putExtra(EXTRA_TARGET_CHANNEL, target.channelId.value)
        putExtra(EXTRA_TARGET_GUILD, target.guildId?.value ?: NO_ID)
    }
    return PendingIntent.getActivity(
        this,
        target.channelId.value.hashCode(),
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Reads the target written by [channelPendingIntent]; null for an intent that is a plain launch. */
fun Intent.notificationTarget(): NotificationTarget? {
    val channel = getLongExtra(EXTRA_TARGET_CHANNEL, NO_ID).takeIf { it != NO_ID } ?: return null
    val guild = getLongExtra(EXTRA_TARGET_GUILD, NO_ID).takeIf { it != NO_ID }
    return NotificationTarget(guildId = guild?.let(::GuildId), channelId = ChannelId(channel))
}
