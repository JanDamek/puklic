package dev.puklic.voice.transport

/**
 * Pure RTP header framing for Discord voice + video.
 *
 * Layout per architect reports 2026-05-23-voice.md §6 (Opus audio) and
 * 2026-05-23-screenshare.md §5 (H.264 / VP8 video):
 * ```
 * byte 0:    0x80              version=2, padding=0, ext=0, csrc=0
 * byte 1:    M(1) | PT(7)      marker bit + payload type
 * bytes 2-3: sequence (u16 BE)
 * bytes 4-7: timestamp (u32 BE)
 * bytes 8-11: ssrc (u32 BE)
 * ```
 */
public object RtpPacket {

    public const val HEADER_SIZE: Int = 12
    public const val VERSION_FLAGS: Byte = 0x80.toByte()
    public const val PAYLOAD_TYPE_OPUS: Byte = 0x78.toByte() // 120
    public const val PAYLOAD_TYPE_H264: Byte = 0x65.toByte() // 101
    public const val PAYLOAD_TYPE_VP8: Byte = 0x67.toByte()  // 103

    private const val MARKER_BIT: Int = 0x80
    private const val PAYLOAD_TYPE_MASK: Int = 0x7F

    public fun writeHeader(
        sequence: Short,
        timestamp: Int,
        ssrc: Int,
        payloadType: Byte = PAYLOAD_TYPE_OPUS,
        marker: Boolean = false,
    ): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = VERSION_FLAGS
        val markerBits = if (marker) MARKER_BIT else 0
        out[1] = (markerBits or (payloadType.toInt() and PAYLOAD_TYPE_MASK)).toByte()
        val seqInt = sequence.toInt() and 0xFFFF
        out[2] = ((seqInt ushr 8) and 0xFF).toByte()
        out[3] = (seqInt and 0xFF).toByte()
        out[4] = ((timestamp ushr 24) and 0xFF).toByte()
        out[5] = ((timestamp ushr 16) and 0xFF).toByte()
        out[6] = ((timestamp ushr 8) and 0xFF).toByte()
        out[7] = (timestamp and 0xFF).toByte()
        out[8] = ((ssrc ushr 24) and 0xFF).toByte()
        out[9] = ((ssrc ushr 16) and 0xFF).toByte()
        out[10] = ((ssrc ushr 8) and 0xFF).toByte()
        out[11] = (ssrc and 0xFF).toByte()
        return out
    }

    public fun readHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_SIZE) { "RTP header requires $HEADER_SIZE bytes, got ${bytes.size}" }
        require(bytes[0] == VERSION_FLAGS) { "Unsupported RTP version/flags byte: 0x${(bytes[0].toInt() and 0xFF).toString(16)}" }
        val b1 = bytes[1].toInt() and 0xFF
        val marker = (b1 and MARKER_BIT) != 0
        val payloadType = (b1 and PAYLOAD_TYPE_MASK).toByte()
        require(payloadType == PAYLOAD_TYPE_OPUS || payloadType == PAYLOAD_TYPE_H264 || payloadType == PAYLOAD_TYPE_VP8) {
            "Unsupported RTP payload type: 0x${(payloadType.toInt() and 0xFF).toString(16)}"
        }
        val seq = (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)).toShort()
        val ts = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        val ssrc = ((bytes[8].toInt() and 0xFF) shl 24) or
            ((bytes[9].toInt() and 0xFF) shl 16) or
            ((bytes[10].toInt() and 0xFF) shl 8) or
            (bytes[11].toInt() and 0xFF)
        return Header(seq, ts, ssrc, payloadType, marker)
    }

    public data class Header(
        val sequence: Short,
        val timestamp: Int,
        val ssrc: Int,
        val payloadType: Byte = PAYLOAD_TYPE_OPUS,
        val marker: Boolean = false,
    )
}
