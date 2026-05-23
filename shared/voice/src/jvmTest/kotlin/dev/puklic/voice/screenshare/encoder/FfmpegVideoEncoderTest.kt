package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Smoke test for [FfmpegVideoEncoder] using `-f lavfi -i testsrc` so no real screen
 * capture (and no Screen Recording permission prompt) is required. The test is gated
 * by either the `puklic.tests.ffmpeg=true` system property or by ffmpeg being
 * locate-able on the current host; when neither holds the test is skipped via JUnit
 * `Assumptions` (treated as a pass + skip note by Gradle).
 */
class FfmpegVideoEncoderTest {

    @Test
    fun `emits at least one keyframe and monotonically increasing timestamps`() = runBlocking {
        val ffmpeg = resolveFfmpegOrSkip()
        val encoder = FfmpegVideoEncoder(
            source = DUMMY_SOURCE,
            shareAudio = false,
            framerate = 30,
            bitrate = 500_000,
            ffmpegPath = ffmpeg,
            inputArgsOverride = listOf(
                "-f", "lavfi",
                "-i", "testsrc=duration=2:size=320x240:rate=30",
            ),
        )

        val frames = withTimeout(SMOKE_TIMEOUT_MS) {
            encoder.encode().take(MIN_FRAMES_TO_COLLECT).toList()
        }

        encoder.stop()

        (frames.isNotEmpty()) shouldBe true
        frames.any { it.keyframe } shouldBe true

        // VCL NAL timestamps must be monotonic non-decreasing (non-VCL NALs share the
        // pts of the following VCL NAL, so equal pts between consecutive NALs is OK).
        val tsList = frames.map { it.ts90k }
        tsList.zipWithNext().all { (prev, next) -> next >= prev } shouldBe true
    }

    private fun resolveFfmpegOrSkip(): String {
        val located = FfmpegVideoEncoder.locateFfmpeg()
        val exists = located.startsWith("/") && File(located).canExecute() ||
            // PATH fallback: try a quick `which` probe.
            runCatching {
                val p = ProcessBuilder("which", located).start()
                p.waitFor()
                p.exitValue() == 0
            }.getOrDefault(false)
        assumeTrue(exists, "ffmpeg not available on PATH; skipping smoke test.")
        return located
    }

    private companion object {
        val DUMMY_SOURCE: ScreenSource = ScreenSource.Monitor(
            id = "0",
            displayName = "test",
            widthPx = 320,
            heightPx = 240,
        )
        const val SMOKE_TIMEOUT_MS = 15_000L
        const val MIN_FRAMES_TO_COLLECT = 5
    }
}
