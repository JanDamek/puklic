package dev.puklic.voice.transport

/**
 * VP8 RTP packetisation per RFC 7741 §4 — single-octet payload descriptor profile.
 *
 * The single-octet descriptor is sufficient for the libvpx output configured by
 * [dev.puklic.voice.screenshare.encoder.LibavVideoEncoder] (single-partition VP8 frames, no
 * PictureID, no temporal layers). Layout:
 *
 * ```
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |X|R|N|S|R| PID |
 * +-+-+-+-+-+-+-+-+
 * ```
 *
 *  - `X` (Extended control bits): 0 — no PictureID / TL0PICIDX / TID extensions.
 *  - `R` (Reserved): 0 — MUST be zero per RFC 7741.
 *  - `N` (Non-reference frame): 0 — libvpx-rt always references previous frame.
 *  - `S` (Start of VP8 partition): 1 on the FIRST RTP packet of a VP8 frame, 0 on subsequent.
 *  - `PID` (Partition index): 0 — single-partition output.
 *
 * Fragmentation splits the encoded VP8 frame into chunks of `MAX_PAYLOAD - DESCRIPTOR_BYTES`
 * bytes; each chunk gets its own 1-byte descriptor prepended. The marker bit (RFC 3550) is set
 * by [VideoRtpSender] based on the last [Fragment]'s `end = true` flag.
 */
internal object Vp8Packetiser : VideoFrameFragmenter {

    /** Safe UDP MTU after RTP header (12), AEAD tag (16), and nonce counter (4). */
    private const val MTU: Int = 1200
    private const val RTP_OVERHEAD: Int = 12 + 16 + 4
    internal const val MAX_PAYLOAD: Int = MTU - RTP_OVERHEAD

    private const val DESCRIPTOR_BYTES: Int = 1

    /** Descriptor with S=1 (start of partition), X=R=N=PID=0. */
    private const val DESCRIPTOR_START: Byte = 0x10
    /** Descriptor with S=0 (continuation). */
    private const val DESCRIPTOR_CONT: Byte = 0x00

    override fun fragment(frame: EncodedFrame): List<Fragment> {
        val bytes = frame.bytes
        if (bytes.isEmpty()) return emptyList()
        val chunkSize = MAX_PAYLOAD - DESCRIPTOR_BYTES
        require(chunkSize > 0) { "MTU too small for VP8 descriptor + payload" }

        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        val out = ArrayList<Fragment>(chunkCount)
        var off = 0
        var index = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkSize, bytes.size)
            val len = end - off
            val payload = ByteArray(DESCRIPTOR_BYTES + len)
            payload[0] = if (index == 0) DESCRIPTOR_START else DESCRIPTOR_CONT
            bytes.copyInto(payload, destinationOffset = DESCRIPTOR_BYTES, startIndex = off, endIndex = end)
            out += Fragment(payload, end = end == bytes.size)
            off = end
            index++
        }
        return out
    }
}
