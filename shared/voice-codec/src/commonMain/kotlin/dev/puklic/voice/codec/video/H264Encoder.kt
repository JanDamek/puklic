package dev.puklic.voice.codec.video

import dev.puklic.voice.transport.EncodedFrame

/**
 * Push-frame H.264 encoder. The caller supplies raw YUV420P bytes per frame; the encoder
 * returns one Annex-B [EncodedFrame] (NAL unit) when output is available, or `null` when the
 * encoder buffered the input without producing output yet.
 *
 * KMP-clean contract — no platform deps in the interface. Platform implementations:
 *
 * - **iOS / macOS App Store** (FP-5) — VideoToolbox `VTCompressionSession`.
 * - **JVM desktop** — not implemented; the existing screen-capture pipeline in
 *   `:shared:voice` (`VideoEncoder` / `LibavVideoEncoder`) is source-driven, not
 *   push-frame, so it does not implement this interface. A push-frame libavcodec impl can
 *   land when a JVM caller actually needs one (none exists today). See
 *   `docs/03_infrastructure/architect-reports/2026-05-29-fp2-h264-interfaces.md` §4.
 *
 * Lifecycle: implementations are stateful (DPB, rate-control). Call [close] to release the
 * underlying codec session. Calling [encode] after [close] is undefined.
 */
public interface H264Encoder : AutoCloseable {
    /**
     * Encode one frame of YUV420P pixel data.
     *
     * @param yuv420p planar YUV 4:2:0 pixels, size `width * height * 3 / 2` bytes
     * @return one Annex-B NAL unit when ready, or `null` when the encoder buffered the
     *   input without emitting output (e.g. B-frame reordering — not used by our preset
     *   but allowed by the contract).
     */
    public fun encode(yuv420p: ByteArray): EncodedFrame?

    override fun close()
}

/**
 * Factory for [H264Encoder]. Constructor parameters live here instead of on the interface
 * so platforms can wire dependencies via DI without reflection.
 */
public interface H264EncoderFactory {
    public fun create(
        width: Int,
        height: Int,
        bitrateKbps: Int,
        framerate: Int,
    ): H264Encoder
}
