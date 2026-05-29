package dev.puklic.voice.codec.video

/**
 * H.264 decoder. The caller supplies one Annex-B NAL unit per call; the decoder returns
 * the decoded ARGB pixels when a frame is ready, or `null` when the NAL was a parameter
 * set (SPS / PPS) or otherwise produced no displayable output.
 *
 * KMP-clean contract — no platform deps in the interface. Platform implementations:
 *
 * - **iOS / macOS App Store** (FP-5) — VideoToolbox `VTDecompressionSession`.
 * - **JVM desktop** — not implemented; no current desktop caller decodes incoming video
 *   (Discord screen-cast is one-way out from desktop). A push-frame libavcodec decoder
 *   can land when a JVM caller actually needs one. See
 *   `docs/03_infrastructure/architect-reports/2026-05-29-fp2-h264-interfaces.md` §4.
 *
 * Output format: ARGB `Int` per pixel (A in the high byte). Length `width * height`.
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
     * @return ARGB pixel array (length `width * height`) when a frame is ready, or `null`
     *   when the input was a parameter set or buffered without producing output.
     */
    public fun decode(annexBNalUnit: ByteArray): IntArray?

    override fun close()
}

/**
 * Factory for [H264Decoder]. Constructor parameters live here instead of on the interface
 * so platforms can wire dependencies via DI without reflection.
 */
public interface H264DecoderFactory {
    public fun create(width: Int, height: Int): H264Decoder
}
