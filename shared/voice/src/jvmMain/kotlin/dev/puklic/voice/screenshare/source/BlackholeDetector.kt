package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.encoder.FfmpegVideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Detects whether the BlackHole 2ch virtual audio device is installed on this macOS host.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §9
 * (UI), the "Share system audio" checkbox is enabled only when BlackHole is present, since the
 * ffmpeg audio capture path on macOS routes through it.
 *
 * Method: spawn `ffmpeg -f avfoundation -list_devices true` and look for a "BlackHole" entry in
 * the audio devices section. Exits non-zero by design (empty `-i ""`) — that's expected.
 */
public object BlackholeDetector {
    private const val LIST_TIMEOUT_S = 5L
    private const val BLACKHOLE_KEYWORD = "BlackHole"

    public suspend fun isAvailable(
        ffmpegPath: String = FfmpegVideoEncoder.locateFfmpeg(),
    ): Boolean = withContext(Dispatchers.IO) {
        val proc = runCatching {
            ProcessBuilder(
                ffmpegPath,
                "-hide_banner",
                "-f", "avfoundation",
                "-list_devices", "true",
                "-i", "",
            ).redirectErrorStream(true).start()
        }.getOrNull() ?: return@withContext false

        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(LIST_TIMEOUT_S, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
        }
        output.contains(BLACKHOLE_KEYWORD, ignoreCase = true)
    }
}
