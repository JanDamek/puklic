package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
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

    // --- helpers ---

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
