package dev.puklic.platform.ios

import dev.puklic.platform.PlatformAutoStart
import dev.puklic.platform.PlatformUnavailable

/**
 * iOS apps cannot toggle launch-at-boot — the sandbox prohibits it.
 * This is a permanent OS fact, not a deferred feature.
 */
class IosPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false
    override suspend fun isEnabled(): Boolean =
        throw PlatformUnavailable("iOS does not allow apps to control launch-at-boot")
    override suspend fun setEnabled(enabled: Boolean): Unit =
        throw PlatformUnavailable("iOS does not allow apps to control launch-at-boot")
}
