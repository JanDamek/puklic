package dev.puklic.voice.audio.macos

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Unit tests for [MacosBlackholeAudioReader].
 *
 * Per repo `CLAUDE.md` HARD RULE #2, the capture pipeline must be conceptually complete
 * at the `Flow<EncodedAudioPacket>` boundary even though transport wiring is blocked on
 * Discord soundshare-SSRC research (issue #25 prerequisite 1). These tests assert that
 * boundary: error handling when BlackHole is missing, URL construction for the libavdevice
 * avfoundation demuxer, and frame-size constants matching the stereo Opus contract.
 */
class MacosBlackholeAudioReaderTest {

    @Test
    fun `start with missing device uid yields actionable installation error`() = runBlocking {
        val reader = MacosBlackholeAudioReader(deviceIndex = null)

        val result = reader.start()

        result.isFailure shouldBe true
        val msg = result.exceptionOrNull()?.message.orEmpty()
        msg.shouldContain("BlackHole")
        msg.shouldContain("existential.audio/blackhole")
    }

    @Test
    fun `audio-only avfoundation url is colon-prefixed audio index`() {
        // libavdevice avfoundation URL is "<video>:<audio>" — audio-only capture leaves the
        // video slot empty: ":<audioIdx>". This guards the URL contract without spinning up
        // ffmpeg.
        MacosBlackholeAudioReader.audioOnlyAvfoundationUrl("1") shouldBe ":1"
        MacosBlackholeAudioReader.audioOnlyAvfoundationUrl("42") shouldBe ":42"
    }

    @Test
    fun `frame size constants match Discord stereo 48kHz 20ms opus contract`() {
        MacosBlackholeAudioReader.BLACKHOLE_SAMPLE_RATE_HZ shouldBe 48_000
        MacosBlackholeAudioReader.BLACKHOLE_CHANNELS shouldBe 2
        MacosBlackholeAudioReader.BLACKHOLE_FRAME_SAMPLES_PER_CHANNEL shouldBe 960
        // 20 ms × 48 kHz = 960 samples per channel
        (MacosBlackholeAudioReader.BLACKHOLE_FRAME_SAMPLES_PER_CHANNEL * 1000 /
            MacosBlackholeAudioReader.BLACKHOLE_SAMPLE_RATE_HZ) shouldBe 20
    }

    /**
     * Integration probe: only meaningful when BlackHole 2ch is actually installed AND the
     * tests run on macOS. Otherwise we skip silently — guarded by [shouldRunIntegration].
     * On a real BlackHole host with no audio routed in, the device still produces silent
     * frames at the configured rate, so we should see at least one [EncodedAudioPacket]
     * within a generous timeout.
     */
    @Test
    fun `integration — collects packets from real BlackHole when installed`() = runBlocking {
        if (!shouldRunIntegration()) return@runBlocking
        val idx = dev.puklic.voice.screenshare.source.BlackholeDetector.findDeviceIndex()
        if (idx == null) return@runBlocking

        val reader = MacosBlackholeAudioReader(deviceIndex = idx)
        val startResult = reader.start()
        startResult.isSuccess shouldBe true

        val packets = withTimeoutOrNull(INTEGRATION_TIMEOUT_MS) {
            reader.packets.take(INTEGRATION_FRAMES).toList()
        }
        reader.stop()

        (packets?.size ?: 0) shouldBe INTEGRATION_FRAMES
    }

    private fun shouldRunIntegration(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac") &&
            System.getProperty("puklic.test.blackhole.integration") == "true"

    private companion object {
        const val INTEGRATION_TIMEOUT_MS = 5_000L
        const val INTEGRATION_FRAMES = 5
    }
}
