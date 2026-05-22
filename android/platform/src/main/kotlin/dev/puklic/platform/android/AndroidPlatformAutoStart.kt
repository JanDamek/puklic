package dev.puklic.platform.android

import dev.puklic.platform.PlatformAutoStart

/** Android does not expose a standard launch-at-boot toggle to apps without special permissions. */
class AndroidPlatformAutoStart : PlatformAutoStart {
    override val supported: Boolean = false
    override suspend fun isEnabled(): Boolean = throw NotImplementedError(PHASE_2)
    override suspend fun setEnabled(enabled: Boolean): Unit = throw NotImplementedError(PHASE_2)

    private companion object {
        const val PHASE_2 = "AndroidPlatformAutoStart: Phase 2"
    }
}
