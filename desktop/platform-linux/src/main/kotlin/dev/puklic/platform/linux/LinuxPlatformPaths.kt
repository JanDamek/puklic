package dev.puklic.platform.linux

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformPaths
import java.io.File

/**
 * XDG Base Directory compliant paths.
 *
 * Spec: https://specifications.freedesktop.org/basedir-spec/latest/
 */
class LinuxPlatformPaths(
    private val env: (String) -> String? = System::getenv,
    private val userHome: String = System.getProperty("user.home"),
    private val appDirName: String = APP_DIR_NAME,
) : PlatformPaths {

    override val dataDir: Path by lazy {
        xdgOrDefault("XDG_DATA_HOME", "$userHome/.local/share").appendApp().ensureExists()
    }

    override val cacheDir: Path by lazy {
        xdgOrDefault("XDG_CACHE_HOME", "$userHome/.cache").appendApp().ensureExists()
    }

    override val configDir: Path by lazy {
        xdgOrDefault("XDG_CONFIG_HOME", "$userHome/.config").appendApp().ensureExists()
    }

    override val crashDir: Path by lazy {
        File(dataDir, "crashes").absolutePath.ensureExists()
    }

    override fun databaseFile(): Path = File(dataDir, "puklic.db").absolutePath

    private fun xdgOrDefault(envName: String, default: String): String {
        val value = env(envName)
        return if (value.isNullOrBlank()) default else value
    }

    private fun String.appendApp(): String = File(this, appDirName).absolutePath

    private fun String.ensureExists(): String {
        File(this).also { if (!it.exists()) it.mkdirs() }
        return this
    }

    companion object {
        internal const val APP_DIR_NAME = "puklic"
    }
}
