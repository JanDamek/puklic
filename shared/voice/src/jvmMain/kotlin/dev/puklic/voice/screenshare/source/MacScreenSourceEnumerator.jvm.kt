package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.screenshare.encoder.FfmpegVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * macOS implementation: spawns `ffmpeg -f avfoundation -list_devices true -i ""` and parses
 * its stderr (merged into stdout via `redirectErrorStream`) for entries in the video section
 * that look like display captures. Window enumeration is layered on top via `osascript` —
 * see [AppleScriptWindowEnumerator] and architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §7.
 *
 * `ffmpeg -list_devices` exits non-zero (1) because the empty `-i ""` is invalid input — that's
 * expected and harmless; we read the listing from the merged stream before the process exits.
 *
 * NOTE on window capture: avfoundation cannot capture an individual window by id; the encoder
 * falls back to fullscreen capture of monitor 0 when the chosen source is a [ScreenSource.Window].
 * The picker still exposes the list so users can pick by window; precise per-window capture is
 * deferred until a ScreenCaptureKit-based input lands (Phase 4.0.2).
 */
internal class MacScreenSourceEnumerator(
    private val ffmpegPath: String = FfmpegVideoEncoder.locateFfmpeg(),
    private val windowEnumerator: WindowEnumerator = AppleScriptWindowEnumerator(),
) : ScreenSourceEnumerator {

    override suspend fun list(): List<ScreenSource> = withContext(Dispatchers.IO) {
        // Phase 2: prefer in-process libavdevice enumeration. Fall back to ffmpeg subprocess
        // only when libavdevice returns nothing (some avfoundation builds return -ENOSYS).
        // The CLI fallback is also skipped when the user has explicitly disabled it via
        // -Dpuklic.voice.encoder=libav (the default), since the goal of the self-contained
        // refactor is to avoid spawning ffmpeg at all.
        val libavMonitors = runCatching {
            LibavMonitorEnumerator.listMonitors("avfoundation")
        }.getOrDefault(emptyList())

        val monitors: List<ScreenSource> = if (libavMonitors.isNotEmpty() || !cliFallbackEnabled()) {
            libavMonitors
        } else {
            subprocessMonitors()
        }
        val windows = runCatching { windowEnumerator.listWindows() }.getOrDefault(emptyList())
        monitors + windows
    }

    private fun subprocessMonitors(): List<ScreenSource> {
        val proc = ProcessBuilder(
            ffmpegPath,
            "-hide_banner",
            "-f", "avfoundation",
            "-list_devices", "true",
            "-i", "",
        ).redirectErrorStream(true).start()

        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(LIST_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        return AvfoundationListParser.parse(output)
    }

    private fun cliFallbackEnabled(): Boolean =
        System.getProperty("puklic.voice.encoder", "libav") == "cli" ||
            System.getProperty("puklic.voice.source-enum-fallback", "true") == "true"

    private companion object {
        const val LIST_TIMEOUT_S = 5L
    }
}

/** Pluggable window enumerator — separated for testability. */
internal interface WindowEnumerator {
    fun listWindows(): List<ScreenSource.Window>
}

/**
 * Enumerates visible application windows via `osascript`. Requires "Automation" permission for
 * the host JVM binary on first run (user is prompted by the OS). On non-mac hosts or when the
 * permission is denied, returns an empty list.
 */
internal class AppleScriptWindowEnumerator(
    private val timeoutSeconds: Long = OSASCRIPT_TIMEOUT_S,
) : WindowEnumerator {

    override fun listWindows(): List<ScreenSource.Window> {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return emptyList()
        val proc = ProcessBuilder(
            "osascript",
            "-e", "tell application \"System Events\"",
            "-e", "set winList to {}",
            "-e", "repeat with proc in (application processes whose visible is true)",
            "-e", "  try",
            "-e", "    repeat with win in (windows of proc)",
            "-e", "      try",
            "-e", "        set winName to name of win",
            "-e", "        if winName is not \"\" then set end of winList to " +
                "(name of proc) & \"|\" & winName",
            "-e", "      end try",
            "-e", "    end repeat",
            "-e", "  end try",
            "-e", "end repeat",
            "-e", "return winList",
            "-e", "end tell",
        ).redirectErrorStream(true).start()

        val out = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return emptyList()
        }
        if (proc.exitValue() != 0) return emptyList()
        return AppleScriptWindowParser.parse(out)
    }

    private companion object {
        const val OSASCRIPT_TIMEOUT_S = 4L
    }
}

/**
 * Parses the raw output of the AppleScript window-listing snippet into [ScreenSource.Window]s.
 * Input form: comma-space-separated `App|Title` records, e.g. `"Safari|Apple, Terminal|zsh"`.
 *
 * The parser is intentionally simple: it splits on `, `, then each piece must contain a single
 * `|` separating non-empty app and title halves. Records with commas inside titles will lose
 * those titles — accepted as a v1 trade-off (see architect report 4.0.1).
 *
 * Ids are synthetic (`win:<index>`) because avfoundation cannot capture by window id; the
 * encoder treats a window source as a fullscreen fallback (monitor 0).
 */
internal object AppleScriptWindowParser {
    private const val ID_PREFIX = "win:"
    private const val SEPARATOR = "|"
    private const val RECORD_DELIMITER = ", "
    private const val UNKNOWN_DIMENSION = 0

    fun parse(raw: String): List<ScreenSource.Window> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(RECORD_DELIMITER)
            .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .mapIndexedNotNull { index, record -> toWindow(index, record) }
    }

    private fun toWindow(index: Int, record: String): ScreenSource.Window? {
        val pipe = record.indexOf(SEPARATOR)
        if (pipe <= 0 || pipe == record.length - 1) return null
        val app = record.substring(0, pipe).trim()
        val title = record.substring(pipe + 1).trim()
        if (app.isEmpty() || title.isEmpty()) return null
        return ScreenSource.Window(
            id = "$ID_PREFIX$index",
            displayName = title,
            appName = app,
            widthPx = UNKNOWN_DIMENSION,
            heightPx = UNKNOWN_DIMENSION,
        )
    }
}

/**
 * Parses `ffmpeg -f avfoundation -list_devices true` output into [ScreenSource]s. Only entries
 * in the `AVFoundation video devices:` section whose name contains "screen" (case-insensitive)
 * become [ScreenSource.Monitor]s — the FaceTime/iSight camera and other non-display cameras
 * are filtered out.
 */
internal object AvfoundationListParser {
    private val deviceLine = Regex("""\[AVFoundation indev[^\]]*\]\s*\[(\d+)\]\s+(.+)""")
    private const val VIDEO_HEADER = "AVFoundation video devices:"
    private const val AUDIO_HEADER = "AVFoundation audio devices:"
    private const val SCREEN_KEYWORD = "screen"
    private const val UNKNOWN_DIMENSION = 0

    fun parse(output: String): List<ScreenSource> {
        var inVideoSection = false
        val sources = mutableListOf<ScreenSource>()
        output.lineSequence().forEach { line ->
            when {
                line.contains(VIDEO_HEADER) -> inVideoSection = true
                line.contains(AUDIO_HEADER) -> inVideoSection = false
                inVideoSection -> {
                    val m = deviceLine.find(line) ?: return@forEach
                    val idx = m.groupValues[1]
                    val name = m.groupValues[2].trim()
                    if (name.contains(SCREEN_KEYWORD, ignoreCase = true)) {
                        sources += ScreenSource.Monitor(
                            id = idx,
                            displayName = name,
                            widthPx = UNKNOWN_DIMENSION,
                            heightPx = UNKNOWN_DIMENSION,
                        )
                    }
                }
            }
        }
        return sources
    }
}

/**
 * JVM actual for [screenSourceEnumerator]. Dispatches by OS:
 *   - macOS → [MacScreenSourceEnumerator] (avfoundation list, plus AppleScript window enum)
 *   - Linux → [LinuxScreenSourceEnumerator] (single synthetic "portal" entry; the actual
 *     monitor selection happens inside xdg-desktop-portal's GUI picker at capture-start time)
 *   - other → Mac enumerator (best-effort; will likely return empty / fail to capture)
 *
 * See architect report `docs/03_infrastructure/architect-reports/2026-05-23-self-contained-linux.md`
 * §4 phase 3.
 */
internal actual fun screenSourceEnumerator(): ScreenSourceEnumerator {
    val osName = System.getProperty("os.name").orEmpty()
    return when {
        osName.contains("Linux", ignoreCase = true) -> LinuxScreenSourceEnumerator()
        else -> MacScreenSourceEnumerator()
    }
}
