package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * H.264 RTP packetization per RFC 6184 §5.8 (FU-A fragmentation).
 *
 * A NAL unit larger than the safe MTU is split into FU-A fragments:
 *  - Indicator byte: F(1)=0 | NRI(2)=copy | Type(5)=28
 *  - FU header byte: S(1)=start | E(1)=end | R(1)=0 | OrigType(5)
 *  - Then a slice of the NAL body (the original first byte is consumed by indicator+header).
 *
 * Single small NAL units fit in one packet and are emitted as the raw NAL.
 */
@PuklicVoiceCodec
public object H264Fragmenter {

    /** Safe UDP MTU after RTP header (12), AEAD tag (16), and nonce counter (4). */
    private const val MTU: Int = 1200
    private const val RTP_OVERHEAD: Int = 12 + 16 + 4
    public const val MAX_PAYLOAD: Int = MTU - RTP_OVERHEAD

    private const val FU_A_TYPE: Int = 28
    private const val NRI_MASK: Int = 0x60
    private const val TYPE_MASK: Int = 0x1F
    private const val FU_HEADER_OVERHEAD: Int = 2 // indicator + FU header

    /** Splits a single H.264 NAL unit (without start code) into a list of RTP payloads (FU-A if needed). */
    fun fragment(nalu: ByteArray): List<Fragment> {
        require(nalu.isNotEmpty()) { "NAL unit must not be empty" }
        if (nalu.size <= MAX_PAYLOAD) return listOf(Fragment(nalu.copyOf(), end = true))

        val firstByte = nalu[0].toInt()
        val nri = firstByte and NRI_MASK
        val origType = firstByte and TYPE_MASK
        val indicator = (nri or FU_A_TYPE).toByte()
        val body = nalu.copyOfRange(1, nalu.size)
        val chunkSize = MAX_PAYLOAD - FU_HEADER_OVERHEAD
        val chunks = mutableListOf<ByteArray>()
        var off = 0
        while (off < body.size) {
            val end = minOf(off + chunkSize, body.size)
            chunks += body.copyOfRange(off, end)
            off = end
        }

        val out = ArrayList<Fragment>(chunks.size)
        chunks.forEachIndexed { i, chunk ->
            val s = if (i == 0) 0x80 else 0x00
            val e = if (i == chunks.lastIndex) 0x40 else 0x00
            val fuHeader = (s or e or origType).toByte()
            val payload = ByteArray(2 + chunk.size)
            payload[0] = indicator
            payload[1] = fuHeader
            chunk.copyInto(payload, destinationOffset = 2)
            out += Fragment(payload, end = i == chunks.lastIndex)
        }
        return out
    }

    /** RTP payload bytes (already FU-A wrapped if needed) plus a flag identifying the last fragment of the NAL. */
    data class Fragment(val payload: ByteArray, val end: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fragment) return false
            return end == other.end && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * payload.contentHashCode() + end.hashCode()
    }
}
