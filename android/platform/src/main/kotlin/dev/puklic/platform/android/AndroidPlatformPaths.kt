package dev.puklic.platform.android

import android.content.Context
import dev.puklic.platform.Path
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformPaths
import java.io.File

/**
 * App-private storage locations of the Android sandbox:
 *  - `dataDir` is `Context.getFilesDir()`
 *  - `cacheDir` is `Context.getCacheDir()` (purgeable by the system)
 *  - `configDir` is `<filesDir>/config`
 *  - `crashDir` is `<noBackupFilesDir>/crashes`
 *  - `databaseFile()` is `Context.getDatabasePath("puklic.db")`, the file opened by `AndroidDriverFactory`
 */
class AndroidPlatformPaths(private val context: Context) : PlatformPaths {
    override val dataDir: Path by lazy { context.filesDir.absolutePath }
    override val cacheDir: Path by lazy { context.cacheDir.absolutePath }
    override val configDir: Path by lazy { ensureDirectory(File(context.filesDir, CONFIG_DIR)) }
    override val crashDir: Path by lazy { ensureDirectory(File(context.noBackupFilesDir, CRASH_DIR)) }
    override fun databaseFile(): Path = context.getDatabasePath(DATABASE_NAME).absolutePath

    private fun ensureDirectory(directory: File): Path {
        if (!directory.isDirectory && !directory.mkdirs()) throw PlatformFailed("Cannot create directory ${directory.path}")
        return directory.absolutePath
    }

    private companion object {
        const val CONFIG_DIR = "config"
        const val CRASH_DIR = "crashes"
        const val DATABASE_NAME = "puklic.db"
    }
}
