package dev.puklic.platform.linux

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class LinuxPlatformPathsTest {

    @Test
    fun `defaults follow XDG conventions when env vars unset`() {
        val tmpHome = Files.createTempDirectory("puklic-home").toFile().absolutePath
        val paths = LinuxPlatformPaths(env = { null }, userHome = tmpHome)

        paths.dataDir shouldBe File(tmpHome, ".local/share/puklic").absolutePath
        paths.cacheDir shouldBe File(tmpHome, ".cache/puklic").absolutePath
        paths.configDir shouldBe File(tmpHome, ".config/puklic").absolutePath
        paths.crashDir shouldBe File(paths.dataDir, "crashes").absolutePath
    }

    @Test
    fun `honours XDG env vars when set`() {
        val tmp = Files.createTempDirectory("puklic-xdg").toFile().absolutePath
        val env = mapOf(
            "XDG_DATA_HOME" to "$tmp/data",
            "XDG_CACHE_HOME" to "$tmp/cache",
            "XDG_CONFIG_HOME" to "$tmp/cfg",
        )
        val paths = LinuxPlatformPaths(env = env::get, userHome = "/should/not/be/used")

        paths.dataDir shouldEndWith "/data/puklic"
        paths.cacheDir shouldEndWith "/cache/puklic"
        paths.configDir shouldEndWith "/cfg/puklic"
    }

    @Test
    fun `creates directories lazily on first access`() {
        val tmpHome = Files.createTempDirectory("puklic-create").toFile().absolutePath
        val paths = LinuxPlatformPaths(env = { null }, userHome = tmpHome)

        assertTrue(File(paths.dataDir).isDirectory)
        assertTrue(File(paths.cacheDir).isDirectory)
        assertTrue(File(paths.configDir).isDirectory)
        assertTrue(File(paths.crashDir).isDirectory)
    }

    @Test
    fun `databaseFile lives under dataDir`() {
        val tmpHome = Files.createTempDirectory("puklic-db").toFile().absolutePath
        val paths = LinuxPlatformPaths(env = { null }, userHome = tmpHome)
        paths.databaseFile() shouldStartWith paths.dataDir
        paths.databaseFile() shouldEndWith "puklic.db"
    }
}
