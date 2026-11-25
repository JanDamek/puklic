package dev.puklic.platform.android

import dev.puklic.platform.PlatformAutoStart
import dev.puklic.platform.PlatformUnavailable

/**
 * Puklic does not start at boot on Android. A boot receiver would gain nothing for a chat client that
 * has no background gateway service, and Android 14+ forbids starting a microphone foreground service
 * from `BOOT_COMPLETED`. This is a permanent platform decision, so the capability is reported as
 * unsupported exactly as on iOS.
 */
class AndroidPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false

    override suspend fun isEnabled(): Boolean = throw PlatformUnavailable(UNSUPPORTED)

    override suspend fun setEnabled(enabled: Boolean): Unit = throw PlatformUnavailable(UNSUPPORTED)

    private companion object {
        const val UNSUPPORTED = "Puklic does not start at boot on Android"
    }
}
