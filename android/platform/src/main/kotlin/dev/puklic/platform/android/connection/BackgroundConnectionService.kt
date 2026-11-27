package dev.puklic.platform.android.connection

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
import dev.puklic.platform.android.FOREGROUND_NOTIFICATION_ID
import dev.puklic.platform.android.launchAppPendingIntent
import dev.puklic.platform.android.registerNotificationChannels
import dev.puklic.platform.android.voice.VoiceForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service of type `specialUse` that keeps the process, and with it the Discord gateway session
 * owned by the application, alive while the UI is closed. `dataSync` is not used because Android 15 stops
 * it after six hours. It shows one low-importance notification that follows the connection status; while a
 * voice call runs, the voice service owns the shared notification id and this service leaves it untouched.
 */
class BackgroundConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing: Job? = null

    private val host: BackgroundConnectionHost
        get() = application as? BackgroundConnectionHost
            ?: error("Application must implement BackgroundConnectionHost")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> host.disableBackgroundConnection()
            else -> {
                enterForeground(BackgroundConnectionStatus.Connecting)
                observeOnce()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observeOnce() {
        if (observing?.isActive == true) return
        observing = scope.launch {
            if (!host.isBackgroundConnectionEnabled()) return@launch stopSelf()
            combine(host.connectionStatus, host.voiceActive, ::Pair).collect { (status, voiceActive) ->
                if (!voiceActive) enterForeground(status)
            }
        }
    }

    private fun enterForeground(status: BackgroundConnectionStatus) {
        registerNotificationChannels()
        ServiceCompat.startForeground(
            this,
            FOREGROUND_NOTIFICATION_ID,
            if (host.voiceActive.value) VoiceForegroundService.buildNotification(this) else buildNotification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun buildNotification(status: BackgroundConnectionStatus): Notification =
        NotificationCompat.Builder(this, AppNotificationChannel.Connection.id)
            .setSmallIcon(R.drawable.ic_stat_puklic)
            .setContentTitle(getString(status.titleRes()))
            .setContentText(getString(R.string.connection_notification_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchAppPendingIntent())
            .addAction(0, getString(R.string.connection_notification_disconnect), disconnectPendingIntent())
            .build()

    private fun BackgroundConnectionStatus.titleRes(): Int = when (this) {
        BackgroundConnectionStatus.Connected -> R.string.connection_notification_title_connected
        BackgroundConnectionStatus.Connecting -> R.string.connection_notification_title_connecting
    }

    private fun disconnectPendingIntent(): PendingIntent {
        val intent = Intent(this, BackgroundConnectionService::class.java).setAction(ACTION_DISCONNECT)
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        private const val ACTION_DISCONNECT = "dev.puklic.android.action.DISCONNECT_BACKGROUND"

        fun startIntent(context: Context): Intent = Intent(context, BackgroundConnectionService::class.java)
    }
}
