package dev.puklic.platform.linux

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformUnavailable
import java.io.File

class LinuxPlatformOpen(
    private val runner: CommandRunner = CommandRunner(),
) : PlatformOpen {

    override suspend fun openUrl(url: String) = invoke(url)

    override suspend fun openFile(path: Path) = invoke(path)

    override suspend fun openInFolder(path: Path) {
        val file = File(path)
        val target = if (file.isDirectory) file.absolutePath else file.parentFile?.absolutePath ?: path
        invoke(target)
    }

    private suspend fun invoke(target: String) {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("$EXEC not installed")
        }
        runner.run(args = listOf(EXEC, target)).successOrThrow(EXEC)
    }

    companion object {
        internal const val EXEC = "xdg-open"

        internal fun argsFor(target: String): List<String> = listOf(EXEC, target)
    }
}
