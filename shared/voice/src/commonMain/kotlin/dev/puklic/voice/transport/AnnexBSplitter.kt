package dev.puklic.voice.transport

/**
 * Splits an H.264 Annex-B byte stream into NAL units by scanning for start codes:
 *  - 4-byte: 0x00 00 00 01
 *  - 3-byte: 0x00 00 01
 *
 * Returns NAL units **without** start code prefixes. The caller is responsible for
 * any subsequent RTP packetization (see [H264Fragmenter]).
 */
internal object AnnexBSplitter {

    fun split(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val out = mutableListOf<ByteArray>()
        var i = 0
        var startNal = -1
        while (i <= bytes.size - 3) {
            val isStart4 = i <= bytes.size - 4 &&
                bytes[i] == 0.toByte() &&
                bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 0.toByte() &&
                bytes[i + 3] == 1.toByte()
            val isStart3 = !isStart4 &&
                bytes[i] == 0.toByte() &&
                bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 1.toByte()
            if (isStart4 || isStart3) {
                if (startNal in 0 until i) out += bytes.copyOfRange(startNal, i)
                val skip = if (isStart4) 4 else 3
                startNal = i + skip
                i += skip
            } else {
                i++
            }
        }
        if (startNal in 0 until bytes.size) out += bytes.copyOfRange(startNal, bytes.size)
        return out
    }
}

/** Encoded video frame (Annex-B bytes, 90 kHz RTP timestamp, keyframe flag). */
data class EncodedFrame(val bytes: ByteArray, val ts90k: Int, val keyframe: Boolean) {
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
