package dev.puklic.platform.ios

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformPaths
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS sandbox-scoped path provider.
 *
 * Mapping (per Apple's File System Programming Guide / App Sandbox):
 *  - `dataDir`   → `<sandbox>/Documents` (user-visible, included in iCloud backup)
 *  - `cacheDir`  → `<sandbox>/Library/Caches` (system-purgeable)
 *  - `configDir` → `<sandbox>/Library/Application Support`
 *  - `crashDir`  → `<dataDir>/crashes` (created)
 *  - `databaseFile()` → `<dataDir>/puklic.db`
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlatformPaths(
    private val fm: NSFileManager = NSFileManager.defaultManager,
) : PlatformPaths {

    override val dataDir: Path by lazy { resolve(NSDocumentDirectory) }
    override val cacheDir: Path by lazy { resolve(NSCachesDirectory) }
    override val configDir: Path by lazy { resolve(NSApplicationSupportDirectory) }
    override val crashDir: Path by lazy { ensureSubdir(dataDir, "crashes") }
    override fun databaseFile(): Path = "$dataDir/puklic.db"

    private fun resolve(directory: NSSearchPathDirectory): String {
        val url: NSURL = fm.URLForDirectory(
            directory = directory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        ) ?: throw PlatformFailed("NSFileManager.URLForDirectory returned null for directory=$directory")
        return url.path ?: throw PlatformFailed("NSURL.path was null for directory=$directory")
    }

    private fun ensureSubdir(parent: String, name: String): String {
        val path = "$parent/$name"
        if (!fm.fileExistsAtPath(path)) {
            fm.createDirectoryAtPath(
                path = path,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        return path
    }
}
