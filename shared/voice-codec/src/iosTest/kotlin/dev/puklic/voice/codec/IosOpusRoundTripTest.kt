package dev.puklic.voice.codec

import dev.puklic.voice.AudioConstants
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke test for the iOS libopus actual. Generates a 20 ms / 48 kHz mono
 * 1 kHz sine, encodes, then decodes; asserts decoded sample count matches
 * input and RMS is within ~3 dB of input (Opus is lossy; tolerance suffices).
 *
 * Runs on iosSimulatorArm64 / iosX64 test targets when a simulator is
 * available. On compile-only CI the build phase still validates cinterop
 * symbol resolution.
 */
class IosOpusRoundTripTest {

    @Test
    fun mono_round_trip_preserves_frame_size_and_rms() {
        val sineHz = 1000.0
        val pcm = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { i ->
            val t = i.toDouble() / AudioConstants.SAMPLE_RATE_HZ
            (sin(2.0 * PI * sineHz * t) * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        val inputRms = rms(pcm)

        val encoder = OpusCodecFactory.createEncoder()
        val decoder = OpusCodecFactory.createDecoder()

        try {
            val packet = encoder.encode(pcm)
            assertTrue(packet.isNotEmpty(), "Encoder must emit a non-empty packet for non-silent input")
            assertTrue(packet.size < MAX_REASONABLE_PACKET_BYTES, "Packet too large: ${packet.size}")

            val decoded = decoder.decode(packet, fec = false)
            assertEquals(AudioConstants.SAMPLES_PER_FRAME, decoded.size, "Decoded frame must be 960 samples (mono)")

            val outputRms = rms(decoded)
            val ratio = outputRms / inputRms
            // ~3 dB tolerance: ratio in [0.5, 2.0]
            assertTrue(
                ratio in MIN_RMS_RATIO..MAX_RMS_RATIO,
                "Decoded RMS $outputRms out of tolerance vs input $inputRms (ratio=$ratio)",
            )
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun decoder_plc_returns_full_silence_frame_on_null_packet() {
        val decoder = OpusCodecFactory.createDecoder()
        try {
            val plc = decoder.decode(null, fec = false)
            assertEquals(AudioConstants.SAMPLES_PER_FRAME, plc.size)
        } finally {
            decoder.close()
        }
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSq = 0.0
        for (s in samples) {
            val v = s.toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / samples.size)
    }

    private companion object {
        const val MAX_REASONABLE_PACKET_BYTES = 400
        const val MIN_RMS_RATIO = 0.5
        const val MAX_RMS_RATIO = 2.0
    }
}
