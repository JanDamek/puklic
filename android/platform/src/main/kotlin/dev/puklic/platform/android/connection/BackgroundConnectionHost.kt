package dev.puklic.platform.android.connection

import kotlinx.coroutines.flow.StateFlow

/** How far the gateway connection kept by [BackgroundConnectionService] has come. */
enum class BackgroundConnectionStatus { Connecting, Connected }

/**
 * What [BackgroundConnectionService] needs from the application: the connection status to show, whether a
 * voice call currently owns the foreground notification, the persisted setting and the way to turn it off.
 * Implemented by the `Application`, which owns the sessions.
 */
interface BackgroundConnectionHost {
    val connectionStatus: StateFlow<BackgroundConnectionStatus>
    val voiceActive: StateFlow<Boolean>

    /** Reads the persisted setting, waiting until the application has loaded it. */
    suspend fun isBackgroundConnectionEnabled(): Boolean

    /** Turns the setting off and stops the service; the "Disconnect" notification action. */
    fun disableBackgroundConnection()
}
