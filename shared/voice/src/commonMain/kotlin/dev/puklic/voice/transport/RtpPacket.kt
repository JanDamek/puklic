package dev.puklic.voice.transport

/**
 * Pure RTP header framing for Discord voice (Opus payload).
 *
 * Layout per architect report 2026-05-23-voice.md §6:
 * ```
 * byte 0:    0x80              version=2, padding=0, ext=0, csrc=0
 * byte 1:    0x78              payload type Opus
 * bytes 2-3: sequence (u16 BE)
 * bytes 4-7: timestamp (u32 BE)
 * bytes 8-11: ssrc (u32 BE)
 * ```
 */
internal object RtpPacket {

    const val HEADER_SIZE: Int = 12
    const val VERSION_FLAGS: Byte = 0x80.toByte()
    const val PAYLOAD_TYPE_OPUS: Byte = 0x78.toByte()

    fun writeHeader(sequence: Short, timestamp: Int, ssrc: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = VERSION_FLAGS
        out[1] = PAYLOAD_TYPE_OPUS
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

    fun readHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_SIZE) { "RTP header requires $HEADER_SIZE bytes, got ${bytes.size}" }
        require(bytes[0] == VERSION_FLAGS) { "Unsupported RTP version/flags byte: 0x${(bytes[0].toInt() and 0xFF).toString(16)}" }
        require(bytes[1] == PAYLOAD_TYPE_OPUS) { "Unsupported RTP payload type: 0x${(bytes[1].toInt() and 0xFF).toString(16)}" }
        val seq = (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)).toShort()
        val ts = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        val ssrc = ((bytes[8].toInt() and 0xFF) shl 24) or
            ((bytes[9].toInt() and 0xFF) shl 16) or
            ((bytes[10].toInt() and 0xFF) shl 8) or
            (bytes[11].toInt() and 0xFF)
        return Header(seq, ts, ssrc)
    }

    data class Header(val sequence: Short, val timestamp: Int, val ssrc: Int)
}
