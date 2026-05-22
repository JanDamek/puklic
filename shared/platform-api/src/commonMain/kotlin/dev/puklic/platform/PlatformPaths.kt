package dev.puklic.platform

/**
 * Canonical filesystem locations for the app, respecting per-OS conventions
 * (XDG on Linux, `Application Support` on macOS, `%AppData%` on Windows, scoped storage on mobile).
 */
interface PlatformPaths {
    val dataDir: Path
    val cacheDir: Path
    val configDir: Path
    val crashDir: Path
    fun databaseFile(): Path
}
