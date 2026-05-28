package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.encoder.FfmpegVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Detects whether the BlackHole 2ch virtual audio device is installed on this macOS host
 * and exposes the avfoundation audio device index needed by [MacosBlackholeAudioReader].
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §9
 * (UI), the "Share system audio" checkbox is enabled only when BlackHole is present.
 *
 * Method: spawn `ffmpeg -f avfoundation -list_devices true` and look for a "BlackHole" entry in
 * the audio devices section. Exits non-zero by design (empty `-i ""`) — that's expected.
 */
public object BlackholeDetector {
    private const val LIST_TIMEOUT_S = 5L
    private const val BLACKHOLE_KEYWORD = "BlackHole"

    public suspend fun isAvailable(
        ffmpegPath: String = FfmpegVideoEncoder.locateFfmpeg(),
    ): Boolean = findDeviceIndex(ffmpegPath) != null

    /**
     * @return the numeric avfoundation audio device index for the first matching BlackHole
     *  device (e.g. "1"), or null when BlackHole is not installed or ffmpeg failed to run.
     *  The returned index is suitable for the libavdevice avfoundation URL of the form
     *  `":<index>"` (audio-only capture — empty video slot).
     */
    public suspend fun findDeviceIndex(
        ffmpegPath: String = FfmpegVideoEncoder.locateFfmpeg(),
    ): String? = withContext(Dispatchers.IO) {
        val proc = runCatching {
            ProcessBuilder(
                ffmpegPath,
                "-hide_banner",
                "-f", "avfoundation",
                "-list_devices", "true",
                "-i", "",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return@withContext null

        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(LIST_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        BlackholeAudioListParser.parseIndex(output)
    }
}

/**
 * Pure parser for `ffmpeg -f avfoundation -list_devices true` output, restricted to the audio
 * device section. Extracted to a top-level object so tests can drive it without spawning
 * ffmpeg (mirrors [AvfoundationListParser]).
 */
internal object BlackholeAudioListParser {
    private val deviceLine = Regex("""\[AVFoundation indev[^\]]*\]\s*\[(\d+)\]\s+(.+)""")
    private const val VIDEO_HEADER = "AVFoundation video devices:"
    private const val AUDIO_HEADER = "AVFoundation audio devices:"
    private const val BLACKHOLE_KEYWORD = "BlackHole"

    fun parseIndex(output: String): String? {
        var inAudioSection = false
        output.lineSequence().forEach { line ->
            when {
                line.contains(VIDEO_HEADER) -> inAudioSection = false
                line.contains(AUDIO_HEADER) -> inAudioSection = true
                inAudioSection -> {
                    val m = deviceLine.find(line) ?: return@forEach
                    val idx = m.groupValues[1]
                    val name = m.groupValues[2].trim()
                    if (name.contains(BLACKHOLE_KEYWORD, ignoreCase = true)) {
                        return idx
                    }
                }
            }
        }
        return null
    }
}
