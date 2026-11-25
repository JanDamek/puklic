package dev.puklic.platform.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.puklic.platform.PlatformPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Presence from the system: the user is away while the screen is off (`PowerManager.isInteractive`),
 * and Do Not Disturb is active while the interruption filter is anything other than "allow all".
 * Both are kept current by protected system broadcasts registered for the lifetime of the process,
 * so one instance is created per application.
 */
class AndroidPlatformPresence(context: Context) : PlatformPresence {
    private val appContext = context.applicationContext
    private val powerManager = checkNotNull(appContext.getSystemService(PowerManager::class.java))
    private val notificationManager = checkNotNull(appContext.getSystemService(NotificationManager::class.java))

    private val away = MutableStateFlow(!powerManager.isInteractive)
    private val dnd = MutableStateFlow(notificationManager.isDndActive())
    override val systemAway: StateFlow<Boolean> = away.asStateFlow()
    override val dndActive: StateFlow<Boolean> = dnd.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun refresh() {
        away.value = !powerManager.isInteractive
        dnd.value = notificationManager.isDndActive()
    }

    private fun NotificationManager.isDndActive(): Boolean =
        currentInterruptionFilter !in INACTIVE_FILTERS

    private companion object {
        val INACTIVE_FILTERS = setOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        )
    }
}
