package dev.puklic.platform.macos

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformPaths
import java.io.File

/**
 * Apple File System Programming Guide locations:
 * - Application Support → ~/Library/Application Support/<App>
 * - Caches              → ~/Library/Caches/<App>
 * - Preferences         → ~/Library/Preferences/<App>
 */
class MacOsPlatformPaths(
    private val userHome: String = System.getProperty("user.home"),
    private val appDirName: String = APP_DIR_NAME,
) : PlatformPaths {

    override val dataDir: Path by lazy {
        File("$userHome/Library/Application Support", appDirName).ensure().absolutePath
    }

    override val cacheDir: Path by lazy {
        File("$userHome/Library/Caches", appDirName).ensure().absolutePath
    }

    override val configDir: Path by lazy {
        File("$userHome/Library/Preferences", appDirName).ensure().absolutePath
    }

    override val crashDir: Path by lazy {
        File(dataDir, "crashes").ensure().absolutePath
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
