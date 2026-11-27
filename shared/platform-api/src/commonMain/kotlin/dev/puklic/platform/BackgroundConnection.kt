package dev.puklic.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps the Discord connection alive while the app UI is closed so incoming messages can still be shown
 * as local notifications. Only platforms with a long-running background service support it.
 */
interface BackgroundConnection {
    /** False where the platform has no such mechanism; the setting is then not offered. */
    val supported: Boolean

    /** True while the user has the feature turned on. */
    val enabled: StateFlow<Boolean>

    /**
     * Turns the feature on or off. Enabling first obtains the permissions it needs, so [enabled]
     * stays false when the user declines them.
     */
    suspend fun setEnabled(enabled: Boolean)
}

/** [BackgroundConnection] for platforms that cannot keep a connection in the background. */
object UnsupportedBackgroundConnection : BackgroundConnection {
    override val supported: Boolean = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun setEnabled(enabled: Boolean): Unit =
        throw PlatformUnavailable("Background connection is not available on this platform")
}
