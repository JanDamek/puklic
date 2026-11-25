package dev.puklic.platform.android.voice

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.puklic.android.platform.R
import dev.puklic.platform.android.AppNotificationChannel
import dev.puklic.platform.android.launchAppPendingIntent
import dev.puklic.platform.android.registerNotificationChannels

/**
 * Foreground service of type `microphone` that keeps the process alive while a voice session is
 * active. It shows one minimal low-importance notification with a "Disconnect" action and is started
 * and stopped by [VoiceServiceController] only around a voice session.
 */
class VoiceForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            else -> enterForeground()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        registerNotificationChannels()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun disconnect() {
        val handler = application as? VoiceDisconnectHandler
            ?: error("Application must implement VoiceDisconnectHandler")
        handler.disconnectVoice()
        stopSelf()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, AppNotificationChannel.Voice.id)
            .setSmallIcon(R.drawable.ic_stat_puklic)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(getString(R.string.voice_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchAppPendingIntent())
            .addAction(0, getString(R.string.voice_notification_disconnect), disconnectPendingIntent())
            .build()

    private fun disconnectPendingIntent(): PendingIntent {
        val intent = Intent(this, VoiceForegroundService::class.java).setAction(ACTION_DISCONNECT)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val ACTION_DISCONNECT = "dev.puklic.android.action.DISCONNECT_VOICE"
        private const val NOTIFICATION_ID = 2_001

        internal fun startIntent(context: Context): Intent = Intent(context, VoiceForegroundService::class.java)
    }
}
