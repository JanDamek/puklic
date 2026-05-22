package dev.puklic.platform.macos

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class MacOsPlatformPathsTest {

    @Test
    fun `Library subdirectories follow Apple conventions`() {
        val tmpHome = Files.createTempDirectory("puklic-mac-home").toFile().absolutePath
        val paths = MacOsPlatformPaths(userHome = tmpHome)

        paths.dataDir shouldBe File("$tmpHome/Library/Application Support/Puklic").absolutePath
        paths.cacheDir shouldBe File("$tmpHome/Library/Caches/Puklic").absolutePath
        paths.configDir shouldBe File("$tmpHome/Library/Preferences/Puklic").absolutePath
        paths.crashDir shouldBe File(paths.dataDir, "crashes").absolutePath
    }

    @Test
    fun `directories are created on first access`() {
        val tmpHome = Files.createTempDirectory("puklic-mac-create").toFile().absolutePath
        val paths = MacOsPlatformPaths(userHome = tmpHome)
        assertTrue(File(paths.dataDir).isDirectory)
        assertTrue(File(paths.cacheDir).isDirectory)
        assertTrue(File(paths.configDir).isDirectory)
        assertTrue(File(paths.crashDir).isDirectory)
    }

    @Test
    fun `databaseFile lives under dataDir`() {
        val tmpHome = Files.createTempDirectory("puklic-mac-db").toFile().absolutePath
        val paths = MacOsPlatformPaths(userHome = tmpHome)
        paths.databaseFile() shouldStartWith paths.dataDir
        paths.databaseFile() shouldEndWith "puklic.db"
    }
}
