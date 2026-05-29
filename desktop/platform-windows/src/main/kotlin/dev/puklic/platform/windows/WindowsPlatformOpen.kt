package dev.puklic.platform.windows

import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import dev.puklic.platform.Path
import dev.puklic.platform.PlatformFailed
import dev.puklic.platform.PlatformOpen
import java.io.File

/**
 * Hands URLs / files / folders to the Windows shell via `ShellExecuteW`.
 *
 * `openInFolder` reveals the file in Explorer using
 * `explorer.exe /select,"<path>"`. The argument-string builder is exposed
 * for unit-testing without invoking the native call.
 */
class WindowsPlatformOpen(
    private val shell: Shell = Shell.Native,
) : PlatformOpen {

    override suspend fun openUrl(url: String) {
        invoke(file = url, parameters = null)
    }

    override suspend fun openFile(path: Path) {
        invoke(file = path, parameters = null)
    }

    override suspend fun openInFolder(path: Path) {
        val file = File(path)
        if (file.isDirectory) {
            invoke(file = file.absolutePath, parameters = null)
        } else {
            invoke(file = EXPLORER_EXE, parameters = argsForReveal(file.absolutePath))
        }
    }

    private fun invoke(file: String, parameters: String?) {
        val rc = shell.shellExecute(
            operation = OP_OPEN,
            file = file,
            parameters = parameters,
        )
        if (rc <= SHELL_EXECUTE_ERROR_THRESHOLD) {
            throw PlatformFailed("ShellExecuteW failed (rc=$rc) for file=$file")
        }
    }

    /** Builds the `/select,"<absolutePath>"` parameter for `explorer.exe`. */
    @Suppress("MemberVisibilityCanBePrivate")
    fun argsForReveal(absolutePath: String): String = "/select,\"$absolutePath\""

    /** Test seam — production binds to [Native]; tests bind to a recording fake. */
    interface Shell {
        fun shellExecute(operation: String, file: String, parameters: String?): Int

        object Native : Shell {
            override fun shellExecute(operation: String, file: String, parameters: String?): Int =
                Shell32.INSTANCE
                    .ShellExecute(
                        null as WinDef.HWND?,
                        operation,
                        file,
                        parameters,
                        null,
                        WinUser.SW_SHOWNORMAL,
                    )
                    .toInt()
        }
    }

    companion object {
        internal const val OP_OPEN = "open"
        internal const val EXPLORER_EXE = "explorer.exe"

        // MSDN: ShellExecuteW returns an HINSTANCE; values <= 32 are error codes.
        internal const val SHELL_EXECUTE_ERROR_THRESHOLD: Int = 32
    }
}
