package dev.puklic.voice.audio.macos

import dev.puklic.voice.screenshare.source.BlackholeAudioListParser
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Unit tests for the audio-section parser used by [dev.puklic.voice.screenshare.source.BlackholeDetector]
 * to find the avfoundation audio device index for BlackHole 2ch.
 */
class BlackholeAudioListParserTest {

    @Test
    fun `parses blackhole index from sample list-devices output`() {
        val output = """
            [AVFoundation indev @ 0x1234] AVFoundation video devices:
            [AVFoundation indev @ 0x1234] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1234] [1] Capture screen 0
            [AVFoundation indev @ 0x1234] AVFoundation audio devices:
            [AVFoundation indev @ 0x1234] [0] MacBook Pro Microphone
            [AVFoundation indev @ 0x1234] [1] BlackHole 2ch
        """.trimIndent()

        BlackholeAudioListParser.parseIndex(output) shouldBe "1"
    }

    @Test
    fun `returns null when blackhole missing from audio section`() {
        val output = """
            [AVFoundation indev @ 0x1] AVFoundation audio devices:
            [AVFoundation indev @ 0x1] [0] MacBook Pro Microphone
        """.trimIndent()

        BlackholeAudioListParser.parseIndex(output) shouldBe null
    }

    @Test
    fun `ignores blackhole-named video device — must come from audio section`() {
        // Defensive: a video device that happens to contain "BlackHole" in its name should
        // not be returned as an audio index. (Not realistic, but covers section-state logic.)
        val output = """
            [AVFoundation indev @ 0x1] AVFoundation video devices:
            [AVFoundation indev @ 0x1] [7] BlackHole-fake
            [AVFoundation indev @ 0x1] AVFoundation audio devices:
            [AVFoundation indev @ 0x1] [0] MacBook Pro Microphone
        """.trimIndent()

        BlackholeAudioListParser.parseIndex(output) shouldBe null
    }

    @Test
    fun `returns null on empty output`() {
        BlackholeAudioListParser.parseIndex("") shouldBe null
    }
}
