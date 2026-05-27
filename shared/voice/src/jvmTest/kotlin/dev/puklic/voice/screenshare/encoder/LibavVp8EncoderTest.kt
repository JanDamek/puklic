package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avdevice.avdevice_register_all
import org.bytedeco.ffmpeg.global.avformat.av_find_input_format
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Smoke test for [LibavVideoEncoder] with [VideoCodec.VP8] — Phase 4.1 roadmap item.
 *
 * Drives the encoder with `-f lavfi -i testsrc` so no real screen capture is needed.
 * If the FFmpeg build bundled by `ffmpeg-platform-gpl` lacks `libvpx` (extremely defensive
 * — the GPL build includes it), the test is skipped via JUnit `Assumptions` so CI on hosts
 * without libvpx degrades gracefully rather than reporting a failure.
 */
class LibavVp8EncoderTest {

    @Test
    fun `VP8 encoder emits at least one keyframe and monotonic timestamps`(): Unit = runBlocking {
        ensureLavfiAvailable()
        ensureLibvpxAvailable()

        val encoder = LibavVideoEncoder(
            source = DUMMY_SOURCE,
            shareAudio = false,
            width = TEST_WIDTH,
            height = TEST_HEIGHT,
            framerate = TEST_FRAMERATE,
            bitrate = TEST_BITRATE,
            inputFormatOverride = "lavfi",
            inputUrlOverride = "testsrc=duration=2:size=320x240:rate=30",
            codec = VideoCodec.VP8,
        )

        val frames = withTimeout(SMOKE_TIMEOUT_MS) {
            encoder.encode().take(MIN_FRAMES_TO_COLLECT).toList()
        }
        encoder.stop()

        frames.isNotEmpty() shouldBe true
        frames.any { it.keyframe } shouldBe true

        val ts = frames.map { it.ts90k }
        ts.zipWithNext().all { (a, b) -> b >= a } shouldBe true

        // VP8 keyframe payload starts with the 3-byte "uncompressed header" whose first byte
        // has bit 0 == 0 (key frame). Frame tag bytes after that include the start code
        // 0x9d 0x01 0x2a. Sanity-check at least the first keyframe has the start code.
        val firstKey = frames.first { it.keyframe }.bytes
        val hasStartCode = (0..firstKey.size - 3).any { i ->
            firstKey[i] == VP8_START_CODE_0 &&
                firstKey[i + 1] == VP8_START_CODE_1 &&
                firstKey[i + 2] == VP8_START_CODE_2
        }
        hasStartCode shouldBe true
    }

    private fun ensureLavfiAvailable() {
        avdevice_register_all()
        val ifmt = av_find_input_format("lavfi")
        assumeTrue(ifmt != null, "lavfi input not available in this FFmpeg build; skipping")
    }

    private fun ensureLibvpxAvailable() {
        val enc = avcodec_find_encoder_by_name("libvpx")
        assumeTrue(enc != null, "libvpx encoder not bundled in this FFmpeg build; skipping")
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
        const val SMOKE_TIMEOUT_MS = 30_000L
        const val MIN_FRAMES_TO_COLLECT = 5
        const val VP8_START_CODE_0: Byte = 0x9d.toByte()
        const val VP8_START_CODE_1: Byte = 0x01.toByte()
        const val VP8_START_CODE_2: Byte = 0x2a.toByte()
    }
}
