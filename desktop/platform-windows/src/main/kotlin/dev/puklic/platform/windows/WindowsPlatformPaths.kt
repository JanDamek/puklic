package dev.puklic.platform.windows

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformPaths
import java.io.File

/**
 * Win32 conventional roots:
 *  - Roaming user data → `%APPDATA%` (default `~\AppData\Roaming`).
 *  - Local-only data   → `%LOCALAPPDATA%` (default `~\AppData\Local`).
 *  - Preferences are stored under the roaming dir so they follow the user
 *    across machines that participate in roaming profiles, matching the
 *    macOS `~/Library/Preferences` semantics.
 *  - Logs (incl. crash dumps) go under Local — they're machine-specific.
 */
class WindowsPlatformPaths(
    appData: String? = System.getenv("APPDATA"),
    localAppData: String? = System.getenv("LOCALAPPDATA"),
    userHome: String = System.getProperty("user.home"),
    private val appDirName: String = APP_DIR_NAME,
) : PlatformPaths {

    private val roamingRoot: String = appData ?: "$userHome\\AppData\\Roaming"
    private val localRoot: String = localAppData ?: "$userHome\\AppData\\Local"

    override val dataDir: Path by lazy {
        File(roamingRoot, appDirName).ensure().absolutePath
    }

    override val cacheDir: Path by lazy {
        File("$localRoot\\$appDirName", "cache").ensure().absolutePath
    }

    override val configDir: Path by lazy {
        File(dataDir, "config").ensure().absolutePath
    }

    override val crashDir: Path by lazy {
        File("$localRoot\\$appDirName\\logs", "crashes").ensure().absolutePath
    }

    override fun databaseFile(): Path = File(dataDir, "puklic.db").absolutePath

    private fun File.ensure(): File {
        if (!exists()) mkdirs()
        return this
    }

    companion object {
        internal const val APP_DIR_NAME = "Puklic"
    }
}
