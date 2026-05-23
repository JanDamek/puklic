package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import dev.puklic.voice.screenshare.encoder.FfmpegVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * macOS implementation: spawns `ffmpeg -f avfoundation -list_devices true -i ""` and parses
 * its stderr (merged into stdout via `redirectErrorStream`) for entries in the video section
 * that look like display captures.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §7.
 * `ffmpeg -list_devices` exits non-zero (1) because the empty `-i ""` is invalid input — that's
 * expected and harmless; we read the listing from the merged stream before the process exits.
 */
internal class MacScreenSourceEnumerator(
    private val ffmpegPath: String = FfmpegVideoEncoder.locateFfmpeg(),
) : ScreenSourceEnumerator {

    override suspend fun list(): List<ScreenSource> = withContext(Dispatchers.IO) {
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
        AvfoundationListParser.parse(output)
    }

    private companion object {
        const val LIST_TIMEOUT_S = 5L
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

internal actual fun screenSourceEnumerator(): ScreenSourceEnumerator = MacScreenSourceEnumerator()
