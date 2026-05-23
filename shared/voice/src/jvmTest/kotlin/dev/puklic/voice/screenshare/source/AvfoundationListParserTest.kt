package dev.puklic.voice.screenshare.source

import dev.puklic.voice.screenshare.ScreenSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Slice 4 — parser unit tests for `ffmpeg -f avfoundation -list_devices true` output.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md`
 * §7 (Source picker), only display/screen entries from the video section become
 * [ScreenSource.Monitor]; audio devices and the iSight/FaceTime camera are filtered out.
 */
class AvfoundationListParserTest {

    @Test
    fun `real sample with camera and two screens returns only screens`() {
        val output = """
            [AVFoundation indev @ 0x1234] AVFoundation video devices:
            [AVFoundation indev @ 0x1234] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1234] [1] Capture screen 0
            [AVFoundation indev @ 0x1234] [2] Capture screen 1
            [AVFoundation indev @ 0x1234] AVFoundation audio devices:
            [AVFoundation indev @ 0x1234] [0] MacBook Pro Microphone
            [AVFoundation indev @ 0x1234] [1] BlackHole 2ch
        """.trimIndent()

        val sources = AvfoundationListParser.parse(output)

        sources shouldHaveSize 2
        val first = sources[0].shouldBeInstanceOf<ScreenSource.Monitor>()
        first.id shouldBe "1"
        first.displayName shouldBe "Capture screen 0"
        val second = sources[1].shouldBeInstanceOf<ScreenSource.Monitor>()
        second.id shouldBe "2"
        second.displayName shouldBe "Capture screen 1"
    }

    @Test
    fun `no video devices section returns empty list`() {
        val output = """
            [AVFoundation indev @ 0x1234] AVFoundation audio devices:
            [AVFoundation indev @ 0x1234] [0] MacBook Pro Microphone
        """.trimIndent()

        AvfoundationListParser.parse(output).shouldBeEmpty()
    }

    @Test
    fun `video devices without screen in name are filtered out`() {
        val output = """
            [AVFoundation indev @ 0x1234] AVFoundation video devices:
            [AVFoundation indev @ 0x1234] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1234] [1] OBS Virtual Camera
            [AVFoundation indev @ 0x1234] AVFoundation audio devices:
        """.trimIndent()

        AvfoundationListParser.parse(output).shouldBeEmpty()
    }

    @Test
    fun `audio device named with screen is not picked up (only video section)`() {
        val output = """
            [AVFoundation indev @ 0x1234] AVFoundation video devices:
            [AVFoundation indev @ 0x1234] [0] FaceTime HD Camera
            [AVFoundation indev @ 0x1234] AVFoundation audio devices:
            [AVFoundation indev @ 0x1234] [0] Screen Audio Pass-through
        """.trimIndent()

        AvfoundationListParser.parse(output).shouldBeEmpty()
    }

    @Test
    fun `empty output returns empty list`() {
        AvfoundationListParser.parse("").shouldBeEmpty()
    }
}
