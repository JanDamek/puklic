package dev.puklic.platform.android

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformOpen

/** Phase 1 stub. Phase 2 will dispatch ACTION_VIEW intents. */
class AndroidPlatformOpen : PlatformOpen {
    override suspend fun openUrl(url: String): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun openFile(path: Path): Unit = throw NotImplementedError(PHASE_2)
    override suspend fun openInFolder(path: Path): Unit = throw NotImplementedError(PHASE_2)

    private companion object {
        const val PHASE_2 = "AndroidPlatformOpen: Phase 2"
    }
}
