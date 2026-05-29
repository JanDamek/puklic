package dev.puklic.voice.transport

/**
 * Encoded video frame (Annex-B bytes for H.264 NAL units, 90 kHz RTP timestamp, keyframe flag).
 *
 * KMP-clean — no platform deps. Moved from `:shared:voice` commonMain in FP-2 so the
 * upcoming iOS / macOS App Store builds can reference the same frame type without pulling
 * in the GPL JVM impl. See
 * `docs/03_infrastructure/architect-reports/2026-05-29-fp2-h264-interfaces.md`.
 *
 * Field names and types are unchanged from the pre-FP-2 declaration to avoid churn across
 * the transport, screenshare, and video-RTP call sites.
 *
 * - [bytes] — payload bytes. For H.264, one NAL unit with the Annex-B start code stripped.
 *   For VP8, a full compressed frame.
 * - [ts90k] — RTP timestamp in 90 kHz units. `Int` matches RFC 3550's 32-bit header field.
 * - [keyframe] — true for an IDR / VP8 keyframe.
 */
public data class EncodedFrame(
    val bytes: ByteArray,
    val ts90k: Int,
    val keyframe: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedFrame) return false
        return ts90k == other.ts90k && keyframe == other.keyframe && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + ts90k
        result = 31 * result + keyframe.hashCode()
        return result
    }
}
