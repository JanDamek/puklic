package dev.puklic.platform.android

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformPaths

/**
 * Phase 1 stub. Phase 2 will inject `android.content.Context` and resolve scoped storage:
 *  - dataDir: context.filesDir
 *  - cacheDir: context.cacheDir
 *  - configDir: File(filesDir, "config")
 *  - crashDir: File(filesDir, "crash")
 *  - databaseFile: context.getDatabasePath("puklic.db")
 */
class AndroidPlatformPaths : PlatformPaths {
    override val dataDir: Path get() = throw NotImplementedError(PHASE_2)
    override val cacheDir: Path get() = throw NotImplementedError(PHASE_2)
    override val configDir: Path get() = throw NotImplementedError(PHASE_2)
    override val crashDir: Path get() = throw NotImplementedError(PHASE_2)
    override fun databaseFile(): Path = throw NotImplementedError(PHASE_2)

    private companion object {
        const val PHASE_2 = "AndroidPlatformPaths: Phase 2 (needs Context)"
    }
}
