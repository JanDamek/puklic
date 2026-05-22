package dev.puklic.platform.linux

import dev.puklic.platform.PlatformClipboard
import dev.puklic.platform.PlatformUnavailable

/**
 * Wayland-first clipboard with X11 fallback.
 * - Wayland (`$WAYLAND_DISPLAY` set): `wl-copy` / `wl-paste`
 * - X11 fallback: `xclip -selection clipboard`
 *
 * Image paste support requires Phase 2 work (AWT Toolkit fallback or `wl-copy --type`).
 */
class LinuxPlatformClipboard(
    private val env: (String) -> String? = System::getenv,
    private val runner: CommandRunner = CommandRunner(),
) : PlatformClipboard {

    private val backend: Backend by lazy { detectBackend() }

    override suspend fun setText(text: String) {
        when (val b = backend) {
            is Backend.Wayland -> runner.run(args = listOf(b.copy), stdin = text).successOrThrow(b.copy)
            is Backend.X11 -> runner.run(args = b.copyArgs, stdin = text).successOrThrow(b.copyArgs.first())
            Backend.None -> throw PlatformUnavailable("No clipboard backend (wl-copy or xclip) installed")
        }
    }

    override suspend fun getText(): String? = when (val b = backend) {
        is Backend.Wayland -> runner.run(args = listOf(b.paste)).let { if (it.succeeded) it.stdout else null }
        is Backend.X11 -> runner.run(args = b.pasteArgs).let { if (it.succeeded) it.stdout else null }
        Backend.None -> throw PlatformUnavailable("No clipboard backend (wl-paste or xclip) installed")
    }

    override suspend fun setImage(bytes: ByteArray, mimeType: String) {
        // Phase 2: implement via `wl-copy --type <mime>` (stdin bytes). For now declare unavailable.
        throw PlatformUnavailable("Image clipboard not supported in Phase 1 Linux backend")
    }

    internal sealed interface Backend {
        data class Wayland(val copy: String = "wl-copy", val paste: String = "wl-paste") : Backend
        data class X11(
            val copyArgs: List<String> = listOf("xclip", "-selection", "clipboard"),
            val pasteArgs: List<String> = listOf("xclip", "-selection", "clipboard", "-o"),
        ) : Backend
        data object None : Backend
    }

    private fun detectBackend(): Backend {
        val isWayland = !env("WAYLAND_DISPLAY").isNullOrBlank()
        if (isWayland && runner.isOnPath("wl-copy")) return Backend.Wayland()
        if (runner.isOnPath("xclip")) return Backend.X11()
        if (runner.isOnPath("wl-copy")) return Backend.Wayland()
        return Backend.None
    }
}
