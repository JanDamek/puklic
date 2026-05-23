package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Smoke test for [LibavVideoEncoder] — Self-contained Phase 2.
 *
 * Drives the encoder with `-f lavfi -i testsrc` so no real screen capture and no JVM
 * Screen Recording permission prompt is required. `lavfi` ships inside the GPL FFmpeg
 * build that we depend on (`ffmpeg-platform-gpl:7.1-1.5.11`), so this test should run on
 * every JVM host that can load the JavaCPP natives.
 *
 * If the `lavfi` input format is not available (extremely defensive — should not happen
 * with our dep), the test is skipped via JUnit `Assumptions`.
 */
class LibavVideoEncoderTest {

    @Test
    fun `emits at least one keyframe and monotonically non-decreasing timestamps`(): Unit = runBlocking {
        ensureLavfiAvailable()

        val encoder = LibavVideoEncoder(
            source = DUMMY_SOURCE,
            shareAudio = false,
            width = TEST_WIDTH,
            height = TEST_HEIGHT,
            framerate = TEST_FRAMERATE,
            bitrate = TEST_BITRATE,
            inputFormatOverride = "lavfi",
            inputUrlOverride = "testsrc=duration=2:size=320x240:rate=30",
        )

        val frames = withTimeout(SMOKE_TIMEOUT_MS) {
            encoder.encode().take(MIN_FRAMES_TO_COLLECT).toList()
        }
        encoder.stop()

        frames.isNotEmpty() shouldBe true
        frames.any { it.keyframe } shouldBe true

        val ts = frames.map { it.ts90k }
        ts.zipWithNext().all { (a, b) -> b >= a } shouldBe true
    }

    private fun ensureLavfiAvailable() {
        avdevice_register_all()
        val ifmt = av_find_input_format("lavfi")
        assumeTrue(ifmt != null, "lavfi input not available in this FFmpeg build; skipping")
    }

    private companion object {
        val DUMMY_SOURCE: ScreenSource = ScreenSource.Monitor(
            id = "0",
            displayName = "test",
            widthPx = 320,
            heightPx = 240,
        )
        const val TEST_WIDTH = 320
        const val TEST_HEIGHT = 240
        const val TEST_FRAMERATE = 30
        const val TEST_BITRATE = 500_000L
        const val SMOKE_TIMEOUT_MS = 20_000L
        const val MIN_FRAMES_TO_COLLECT = 5
    }
}
