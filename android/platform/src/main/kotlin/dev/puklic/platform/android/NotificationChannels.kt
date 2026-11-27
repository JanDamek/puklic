package dev.puklic.platform.android

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.StringRes
import dev.puklic.android.platform.R
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat

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
