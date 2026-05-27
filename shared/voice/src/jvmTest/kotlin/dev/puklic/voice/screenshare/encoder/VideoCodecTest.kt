package dev.puklic.voice.screenshare.encoder

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure-unit tests for [VideoCodec] enum mapping and [chooseCodec] SDP-preference logic.
 * These do not touch FFmpeg natives and run on every host.
 */
class VideoCodecTest {

    @Test
    fun `H264 maps to libx264 encoder`() {
        VideoCodec.H264.encoderName shouldBe "libx264"
    }

    @Test
    fun `VP8 maps to libvpx encoder`() {
        VideoCodec.VP8.encoderName shouldBe "libvpx"
    }

    @Test
    fun `chooseCodec prefers H264 when both offered`() {
        chooseCodec(listOf("VP8", "H264")) shouldBe VideoCodec.H264
    }

    @Test
    fun `chooseCodec falls back to VP8 when H264 absent`() {
        chooseCodec(listOf("VP8")) shouldBe VideoCodec.VP8
    }

    @Test
    fun `chooseCodec is case-insensitive`() {
        chooseCodec(listOf("h264")) shouldBe VideoCodec.H264
        chooseCodec(listOf("vp8")) shouldBe VideoCodec.VP8
    }

    @Test
    fun `chooseCodec returns null when neither offered`() {
        chooseCodec(emptyList()) shouldBe null
        chooseCodec(listOf("AV1", "VP9")) shouldBe null
    }
}
