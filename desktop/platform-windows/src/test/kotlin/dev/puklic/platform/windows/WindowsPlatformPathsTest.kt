package dev.puklic.platform.windows

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Path derivation is pure Kotlin — no Windows runtime is needed, so this
 * suite runs on every CI host (Linux / macOS / Windows).
 */
class WindowsPlatformPathsTest {

    @Test
    fun `paths derive from APPDATA and LOCALAPPDATA when set`() {
        val roaming = Files.createTempDirectory("puklic-win-roaming").toFile().absolutePath
        val local = Files.createTempDirectory("puklic-win-local").toFile().absolutePath
        val paths = WindowsPlatformPaths(appData = roaming, localAppData = local)

        paths.dataDir shouldBe File(roaming, "Puklic").absolutePath
        paths.cacheDir shouldBe File("$local\\Puklic", "cache").absolutePath
        paths.configDir shouldBe File(paths.dataDir, "config").absolutePath
        paths.crashDir shouldBe File("$local\\Puklic\\logs", "crashes").absolutePath
    }

    @Test
    fun `paths fall back to user home when env vars absent`() {
        val home = Files.createTempDirectory("puklic-win-home").toFile().absolutePath
        val paths = WindowsPlatformPaths(appData = null, localAppData = null, userHome = home)

        paths.dataDir shouldStartWith home
        paths.cacheDir shouldStartWith home
    }

    @Test
    fun `directories are created on first access`() {
        val roaming = Files.createTempDirectory("puklic-win-roaming-create").toFile().absolutePath
        val local = Files.createTempDirectory("puklic-win-local-create").toFile().absolutePath
        val paths = WindowsPlatformPaths(appData = roaming, localAppData = local)
        assertTrue(File(paths.dataDir).isDirectory)
        assertTrue(File(paths.cacheDir).isDirectory)
        assertTrue(File(paths.configDir).isDirectory)
        assertTrue(File(paths.crashDir).isDirectory)
    }

    @Test
    fun `databaseFile lives under dataDir`() {
        val roaming = Files.createTempDirectory("puklic-win-roaming-db").toFile().absolutePath
        val local = Files.createTempDirectory("puklic-win-local-db").toFile().absolutePath
        val paths = WindowsPlatformPaths(appData = roaming, localAppData = local)
        paths.databaseFile() shouldStartWith paths.dataDir
        paths.databaseFile() shouldEndWith "puklic.db"
    }
}
