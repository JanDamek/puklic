package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Functional smoke tests for the libopus JNA backend.
 *
 * These tests require system libopus to be installed (see jvmMain dependencies block
 * in build.gradle.kts). On CI without libopus, JNA load will fail and these tests will
 * error out with a clear message from [OpusException]. That's intentional — we don't
 * want silent skips that hide regressions.
 */
class OpusCodecTest {

    @Test
    fun `encode 960-sample sine produces non-empty packet under 4000 bytes`() {
        OpusCodecFactory.createEncoder().use { encoder ->
            val frame = sine(440.0, AudioConstants.SAMPLES_PER_FRAME)
            val packet = encoder.encode(frame)
            packet.size shouldBeGreaterThan 0
            packet.size shouldBeLessThan AudioConstants.MAX_OPUS_FRAME_BYTES
        }
    }

    @Test
    fun `decode round-trip returns 960 samples`() {
        OpusCodecFactory.createEncoder().use { encoder ->
            OpusCodecFactory.createDecoder().use { decoder ->
                val frame = sine(440.0, AudioConstants.SAMPLES_PER_FRAME)
                val packet = encoder.encode(frame)
                val decoded = decoder.decode(packet, fec = false)
                decoded.toList() shouldHaveSize AudioConstants.SAMPLES_PER_FRAME
            }
        }
    }

    @Test
    fun `round-trip preserves signal energy within 3 dB for clean sine wave`() {
        // Opus has a ~6.5 ms algorithmic delay (≈312 samples at 48 kHz), so sample-aligned
        // SNR is meaningless for a single frame. Instead, after enough pre-roll frames for
        // the codec to converge, the decoded frame's RMS should match the input RMS within
        // a few dB. This validates that the codec is roundtripping audio energy rather
        // than emitting silence or garbage.
        OpusCodecFactory.createEncoder().use { encoder ->
            OpusCodecFactory.createDecoder().use { decoder ->
                lateinit var original: ShortArray
                lateinit var decoded: ShortArray
                repeat(10) { i ->
                    val phaseOffset = i * AudioConstants.SAMPLES_PER_FRAME
                    val frame = sine(440.0, AudioConstants.SAMPLES_PER_FRAME, phaseOffset)
                    val packet = encoder.encode(frame)
                    val out = decoder.decode(packet, fec = false)
                    original = frame
                    decoded = out
                }
                val origRms = rms(original)
                val decRms = rms(decoded)
                val ratioDb = 20.0 * log10(decRms / origRms)
                assertTrue(
                    ratioDb in -3.0..3.0,
                    "RMS ratio ${ratioDb.roundToInt()} dB outside ±3 dB " +
                        "(orig=${origRms.roundToInt()}, decoded=${decRms.roundToInt()})",
                )
            }
        }
    }

    @Test
    fun `PLC decode with null packet returns 960 samples`() {
        OpusCodecFactory.createDecoder().use { decoder ->
            // Prime the decoder with one real frame so PLC has internal state to extrapolate.
            OpusCodecFactory.createEncoder().use { encoder ->
                decoder.decode(encoder.encode(sine(440.0, AudioConstants.SAMPLES_PER_FRAME)))
            }
            val plc = decoder.decode(opus = null, fec = false)
            plc.toList() shouldHaveSize AudioConstants.SAMPLES_PER_FRAME
        }
    }

    @Test
    fun `encoder rejects wrong frame size`() {
        OpusCodecFactory.createEncoder().use { encoder ->
            val wrong = ShortArray(480)
            try {
                encoder.encode(wrong)
                error("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // -------- stereo (screencast audio path) --------

    @Test
    fun `stereo audio constants are 1 and 2`() {
        AudioConstants.CHANNELS_MONO shouldBe 1
        AudioConstants.CHANNELS_STEREO shouldBe 2
    }

    @Test
    fun `encode stereo 1920-sample interleaved frame produces non-empty packet`() {
        val config = OpusEncoderConfig(
            channels = AudioConstants.CHANNELS_STEREO,
            application = OpusApplication.Audio,
        )
        OpusCodecFactory.createEncoder(config).use { encoder ->
            encoder.channels shouldBe AudioConstants.CHANNELS_STEREO
            val frame = stereoSine(440.0, 660.0, AudioConstants.SAMPLES_PER_FRAME)
            frame.size shouldBe AudioConstants.SAMPLES_PER_FRAME * AudioConstants.CHANNELS_STEREO
            val packet = encoder.encode(frame)
            packet.size shouldBeGreaterThan 0
            packet.size shouldBeLessThan AudioConstants.MAX_OPUS_FRAME_BYTES
        }
    }

    @Test
    fun `stereo decode round-trip returns 1920 interleaved samples`() {
        val encCfg = OpusEncoderConfig(
            channels = AudioConstants.CHANNELS_STEREO,
            application = OpusApplication.Audio,
        )
        OpusCodecFactory.createEncoder(encCfg).use { encoder ->
            OpusCodecFactory.createDecoder(AudioConstants.CHANNELS_STEREO).use { decoder ->
                decoder.channels shouldBe AudioConstants.CHANNELS_STEREO
                val frame = stereoSine(440.0, 660.0, AudioConstants.SAMPLES_PER_FRAME)
                val packet = encoder.encode(frame)
                val decoded = decoder.decode(packet, fec = false)
                decoded.size shouldBe AudioConstants.SAMPLES_PER_FRAME * AudioConstants.CHANNELS_STEREO
            }
        }
    }

    @Test
    fun `stereo round-trip preserves per-channel signal energy within 3 dB`() {
        val encCfg = OpusEncoderConfig(
            channels = AudioConstants.CHANNELS_STEREO,
            application = OpusApplication.Audio,
        )
        OpusCodecFactory.createEncoder(encCfg).use { encoder ->
            OpusCodecFactory.createDecoder(AudioConstants.CHANNELS_STEREO).use { decoder ->
                lateinit var original: ShortArray
                lateinit var decoded: ShortArray
                repeat(10) { i ->
                    val phaseOffset = i * AudioConstants.SAMPLES_PER_FRAME
                    val frame = stereoSine(440.0, 660.0, AudioConstants.SAMPLES_PER_FRAME, phaseOffset)
                    val packet = encoder.encode(frame)
                    val out = decoder.decode(packet, fec = false)
                    original = frame
                    decoded = out
                }
                val origL = rms(deinterleave(original, 2, 0))
                val origR = rms(deinterleave(original, 2, 1))
                val decL = rms(deinterleave(decoded, 2, 0))
                val decR = rms(deinterleave(decoded, 2, 1))
                val ratioLDb = 20.0 * log10(decL / origL)
                val ratioRDb = 20.0 * log10(decR / origR)
                assertTrue(ratioLDb in -3.0..3.0, "L RMS ratio ${ratioLDb.roundToInt()} dB outside ±3 dB")
                assertTrue(ratioRDb in -3.0..3.0, "R RMS ratio ${ratioRDb.roundToInt()} dB outside ±3 dB")
            }
        }
    }

    @Test
    fun `stereo encoder rejects mono-sized frame`() {
        val cfg = OpusEncoderConfig(channels = AudioConstants.CHANNELS_STEREO)
        OpusCodecFactory.createEncoder(cfg).use { encoder ->
            val monoBuf = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
            try {
                encoder.encode(monoBuf)
                error("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `OpusEncoderConfig rejects invalid channel counts`() {
        try {
            OpusEncoderConfig(channels = 3)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            OpusEncoderConfig(channels = 0)
            error("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `stereo decoder of mono packet returns interleaved stereo`() {
        // libopus auto-remixes; verify decoder honours its configured channel count.
        OpusCodecFactory.createEncoder().use { monoEnc ->
            OpusCodecFactory.createDecoder(AudioConstants.CHANNELS_STEREO).use { stereoDec ->
                val pkt = monoEnc.encode(sine(440.0, AudioConstants.SAMPLES_PER_FRAME))
                val decoded = stereoDec.decode(pkt, fec = false)
                decoded.size shouldBe AudioConstants.SAMPLES_PER_FRAME * AudioConstants.CHANNELS_STEREO
            }
        }
    }

    @Test
    fun `mono encoder channels is 1`() {
        OpusCodecFactory.createEncoder().use { encoder ->
            encoder.channels shouldBe AudioConstants.CHANNELS_MONO
        }
    }

    @Test
    fun `mono decoder channels is 1`() {
        OpusCodecFactory.createDecoder().use { decoder ->
            decoder.channels shouldBe AudioConstants.CHANNELS_MONO
        }
    }

    // --- helpers ---

    private fun stereoSine(
        freqLHz: Double,
        freqRHz: Double,
        samplesPerChannel: Int,
        phaseOffset: Int = 0,
    ): ShortArray {
        val out = ShortArray(samplesPerChannel * 2)
        val twoPi = 2.0 * PI
        val wL = twoPi * freqLHz / AudioConstants.SAMPLE_RATE_HZ
        val wR = twoPi * freqRHz / AudioConstants.SAMPLE_RATE_HZ
        for (i in 0 until samplesPerChannel) {
            val l = (sin(wL * (i + phaseOffset)) * 0.5 * Short.MAX_VALUE).toInt().toShort()
            val r = (sin(wR * (i + phaseOffset)) * 0.5 * Short.MAX_VALUE).toInt().toShort()
            out[i * 2] = l
            out[i * 2 + 1] = r
        }
        return out
    }

    private fun deinterleave(interleaved: ShortArray, channels: Int, channelIndex: Int): ShortArray {
        val perChan = interleaved.size / channels
        val out = ShortArray(perChan)
        for (i in 0 until perChan) out[i] = interleaved[i * channels + channelIndex]
        return out
    }

    private fun sine(freqHz: Double, samples: Int, phaseOffset: Int = 0): ShortArray {
        val out = ShortArray(samples)
        val twoPiFOverFs = 2.0 * PI * freqHz / AudioConstants.SAMPLE_RATE_HZ
        for (i in 0 until samples) {
            val v = sin(twoPiFOverFs * (i + phaseOffset))
            // 0.5 amplitude (-6 dBFS) to avoid Opus pre-emphasis clipping artefacts at unity.
            out[i] = (v * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size)
    }
}
