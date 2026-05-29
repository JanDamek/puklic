package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.transport.RtpPacket

/**
 * Video codecs the screen-share encoder can produce. Discord's voice gateway advertises both
 * H.264 and VP8 in Op 1 SelectProtocol (see
 * `VoiceGatewayConnection.DEFAULT_CODECS`); the receiver picks one in its SessionDescription
 * reply. The local libavcodec encoder maps each enum entry to a concrete encoder name from
 * the bundled `ffmpeg-platform-gpl` build:
 *
 *  - [H264] → `libx264` (default; baseline profile, zerolatency tune, Annex-B NAL output)
 *  - [VP8]  → `libvpx` (CBR, GOP-aligned keyframes, IVF-less raw frames)
 *
 * The set is intentionally closed: VP9 / AV1 are not advertised by Discord today, so adding
 * them would be dead code (HARD RULE #2). When Discord starts negotiating them, extend this
 * enum + add the encoder name mapping in `LibavVideoEncoder`.
 */
public enum class VideoCodec(val encoderName: String) {
    H264("libx264"),
    VP8("libvpx"),
    ;

    /**
     * RTP payload type advertised by Discord for this codec (see `VoiceGatewayConnection.DEFAULT_CODECS`):
     *  - H.264 → 101 (0x65)
     *  - VP8   → 103 (0x67)
     */
    fun payloadType(): Byte = when (this) {
        H264 -> RtpPacket.PAYLOAD_TYPE_H264
        VP8 -> RtpPacket.PAYLOAD_TYPE_VP8
    }
}

/**
 * Pick the encoder codec to use given the codecs the receiver advertised in its
 * SessionDescription. H.264 is preferred because the project's RTP packetizer
 * ([dev.puklic.voice.transport.H264Fragmenter]) and decoder path are H.264-native; VP8 is the
 * fallback when H.264 is absent (e.g. an older / browser-based Discord client).
 *
 * @param offered codec names as they appear in Discord's `codecs` array (case-insensitive
 *                match against [VideoCodec.name]).
 * @return the codec to use, or `null` if neither H.264 nor VP8 is offered. Callers MUST treat
 *         null as a hard failure (the call cannot proceed) — there is no third option.
 */
public fun chooseCodec(offered: List<String>): VideoCodec? {
    val normalised = offered.map { it.uppercase() }.toSet()
    return when {
        "H264" in normalised -> VideoCodec.H264
        "VP8" in normalised -> VideoCodec.VP8
        else -> null
    }
}
