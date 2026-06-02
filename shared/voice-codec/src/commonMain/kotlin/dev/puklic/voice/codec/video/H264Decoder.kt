package dev.puklic.voice.codec.video

/**
 * H.264 decoder. The caller supplies one Annex-B NAL unit per call; the decoder returns
 * a [DecodedFrame] when a frame is ready, or `null` when the NAL was a parameter set
 * (SPS / PPS), was buffered without producing output, or otherwise produced no displayable
 * frame yet.
 *
 * KMP-clean contract — no platform deps in the interface. Platform implementations:
 *
 * - **iOS / macOS App Store** — VideoToolbox `VTDecompressionSession` (`IosH264Decoder`,
 *   `JnaVideoToolboxH264Decoder`).
 * - **JVM desktop (GPL)** — libavcodec via JavaCPP (`LibavH264Decoder`, lives in
 *   `:shared:voice/jvmMain` because libavcodec is GPL and may not enter the
 *   `:shared:voice-codec` KMP graph that feeds the Apple App Store builds).
 *
 * Output format: [DecodedFrame.rgba] is packed `RGBA` byte layout — 4 bytes per pixel,
 * order red, green, blue, alpha — `size == width * height * 4`. The dimensions come from
 * the underlying stream (SPS) rather than from the factory; the factory hint values
 * configure the codec session but may be overridden by the actual bitstream.
 *
 * Lifecycle: implementations are stateful (DPB, reference frames). Call [close] to release
 * the underlying codec session.
 */
public interface H264Decoder : AutoCloseable {
    /**
     * Decode one Annex-B NAL unit.
     *
     * @param annexBNalUnit one NAL unit, with or without the Annex-B start code prefix
     *   (implementations strip it if present)
     * @return decoded frame when ready, or `null` if the input was a parameter set or
     *   was buffered without producing output.
     */
    public fun decode(annexBNalUnit: ByteArray): DecodedFrame?

    override fun close()

    /**
     * Decoded RGBA frame: `rgba.size == width * height * 4`, byte layout R G B A per pixel.
     */
    public class DecodedFrame(
        public val rgba: ByteArray,
        public val width: Int,
        public val height: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedFrame) return false
            return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
        }

        override fun hashCode(): Int {
            var h = width
            h = 31 * h + height
            h = 31 * h + rgba.contentHashCode()
            return h
        }
    }
}

/**
 * Factory for [H264Decoder]. Constructor parameters live here instead of on the interface
 * so platforms can wire dependencies via DI without reflection.
 *
 * The width/height are *hints* — Apple VideoToolbox needs them to pre-configure the
 * destination pixel buffer, libavcodec ignores them entirely. Actual frame dimensions come
 * from the SPS in the bitstream.
 */
public interface H264DecoderFactory {
    public fun create(width: Int, height: Int): H264Decoder
}
