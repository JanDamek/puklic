package dev.puklic.voice.codec.video

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke test for the iOS VideoToolbox H.264 actual. Encodes a synthetic 640x360 I420 gray
 * frame and verifies:
 *
 *  - Output starts with the 4-byte Annex-B start code `0x00 0x00 0x00 0x01`.
 *  - The first emitted frame is flagged as a keyframe (IDR).
 *
 * Then feeds the emitted Annex-B back into the decoder one NAL at a time and verifies the
 * decoded ARGB array has the expected size.
 *
 * Runs on iosSimulatorArm64 / iosX64 simulator targets. On compile-only CI the build phase
 * still validates VideoToolbox cinterop symbol resolution.
 */
class IosH264RoundTripTest {

    @Test
    fun encode_gray_frame_emits_annexb_keyframe() {
        val width = 640
        val height = 360
        val encoder = IosH264EncoderFactory.create(
            width = width,
            height = height,
            bitrateKbps = 1_500,
            framerate = 30,
        )
        try {
            val gray = grayI420(width, height)
            // VT may swallow the very first frame during hardware-pipeline warm-up. Try a
            // few frames; assert at least one emits with the expected shape.
            var emitted: dev.puklic.voice.transport.EncodedFrame? = null
            repeat(5) {
                val f = encoder.encode(gray)
                if (f != null && emitted == null) emitted = f
            }
            val frame = assertNotNull(emitted, "VTCompressionSession emitted no frames in 5 pushes")
            assertTrue(frame.bytes.size >= START_CODE_SIZE, "frame too small: ${frame.bytes.size}")
            assertTrue(
                frame.bytes[0].toInt() == 0 &&
                    frame.bytes[1].toInt() == 0 &&
                    frame.bytes[2].toInt() == 0 &&
                    frame.bytes[3].toInt() == 1,
                "frame must start with 0x00 0x00 0x00 0x01 Annex-B start code",
            )
            assertTrue(frame.keyframe, "first emitted frame must be a keyframe (IDR)")
        } finally {
            encoder.close()
        }
    }

    @Test
    fun decode_emitted_frame_returns_pixels_of_expected_size() {
        val width = 640
        val height = 360
        val encoder = IosH264EncoderFactory.create(width, height, 1_500, 30)
        val decoder = IosH264DecoderFactory.create(width, height)
        try {
            val gray = grayI420(width, height)
            var encoded: dev.puklic.voice.transport.EncodedFrame? = null
            repeat(5) {
                val f = encoder.encode(gray)
                if (f != null && encoded == null) encoded = f
            }
            val frame = assertNotNull(encoded, "encoder did not produce a frame")

            // Split the Annex-B payload into individual NAL units and feed them one-by-one.
            val nalus = splitAnnexB(frame.bytes)
            assertTrue(nalus.isNotEmpty(), "expected at least one NAL unit in encoded frame")
            var pixels: IntArray? = null
            for (nalu in nalus) {
                val out = decoder.decode(nalu)
                if (out != null) pixels = out
            }
            val px = assertNotNull(pixels, "decoder produced no pixels for a self-emitted IDR")
            // Decoder may crop to expected dims or return the encoder's padded width if
            // VT pads — accept either >= w*h.
            assertTrue(px.size >= width * height, "decoded pixel count too small: ${px.size}")
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    private fun grayI420(width: Int, height: Int): ByteArray {
        val y = width * height
        val c = (width / 2) * (height / 2)
        val arr = ByteArray(y + 2 * c)
        // Mid-gray luma (128), neutral chroma (128).
        for (i in 0 until y) arr[i] = 128.toByte()
        for (i in y until arr.size) arr[i] = 128.toByte()
        return arr
    }

    private fun splitAnnexB(stream: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        var start = -1
        var i = 0
        while (i + 3 < stream.size) {
            val isFour = stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 &&
                stream[i + 2].toInt() == 0 && stream[i + 3].toInt() == 1
            val isThree = stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 &&
                stream[i + 2].toInt() == 1
            if (isFour) {
                if (start >= 0) out += stream.copyOfRange(start, i)
                start = i + 4
                i += 4
                continue
            }
            if (isThree) {
                if (start >= 0) out += stream.copyOfRange(start, i)
                start = i + 3
                i += 3
                continue
            }
            i += 1
        }
        if (start in 0..stream.size) out += stream.copyOfRange(start, stream.size)
        return out
    }

    private companion object {
        const val START_CODE_SIZE = 4
    }
}
