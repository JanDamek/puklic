package dev.puklic.platform.macos

import dev.puklic.platform.Path
import dev.puklic.platform.PlatformOpen
import dev.puklic.platform.PlatformUnavailable
import java.io.File

class MacOsPlatformOpen(
    private val runner: CommandRunner = CommandRunner(),
) : PlatformOpen {

    override suspend fun openUrl(url: String) = invoke(listOf(EXEC, url))

    override suspend fun openFile(path: Path) = invoke(listOf(EXEC, path))

    override suspend fun openInFolder(path: Path) {
        val file = File(path)
        if (file.isDirectory) {
            invoke(listOf(EXEC, file.absolutePath))
        } else {
            // -R reveals the file in Finder
            invoke(listOf(EXEC, "-R", file.absolutePath))
        }
    }

    private suspend fun invoke(args: List<String>) {
        if (!runner.isOnPath(EXEC)) {
            throw PlatformUnavailable("$EXEC not found")
        }
        runner.run(args).successOrThrow(EXEC)
    }

    companion object {
        internal const val EXEC = "/usr/bin/open"

        internal fun argsForUrl(url: String): List<String> = listOf(EXEC, url)
        internal fun argsForRevealInFinder(absolutePath: String): List<String> = listOf(EXEC, "-R", absolutePath)
    }
}
