package dev.puklic.voice.transport

/**
 * Strategy for packetising an [EncodedFrame] into RTP-ready fragments.
 *
 * Discord screen-share negotiates either H.264 or VP8 per [dev.puklic.voice.gateway.VoiceGatewayConnection]
 * SessionDescription. Each codec ships with its own packetisation RFC:
 *
 *  - H.264 → RFC 6184 §5.8 FU-A (one Annex-B NAL unit per RTP packet, fragmented if larger than MTU).
 *    Implemented by [H264FrameFragmenter] (delegates to [H264Fragmenter] after Annex-B splitting).
 *  - VP8 → RFC 7741 §4 (one-octet descriptor profile sufficient for libvpx single-partition output).
 *    Implemented by [Vp8Packetiser].
 *
 * Both produce a [Fragment] list ordered by send-time; only the LAST [Fragment] of a frame has
 * `end = true`, which [VideoRtpSender] uses to set the RTP marker bit.
 */
internal interface VideoFrameFragmenter {
    fun fragment(frame: EncodedFrame): List<Fragment>
}

/** RTP payload bytes plus a flag identifying the last fragment of the frame. */
internal data class Fragment(val payload: ByteArray, val end: Boolean) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Fragment) return false
        return end == other.end && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * payload.contentHashCode() + end.hashCode()
}

/**
 * H.264 fragmenter strategy. Splits an Annex-B byte stream into NAL units, then fragments each NAL
 * with FU-A per RFC 6184. The LAST fragment of the LAST NAL is marked `end=true` so the marker bit
 * lands on the final RTP packet of the frame.
 */
internal object H264FrameFragmenter : VideoFrameFragmenter {
    override fun fragment(frame: EncodedFrame): List<Fragment> {
        val nalus = AnnexBSplitter.split(frame.bytes)
        if (nalus.isEmpty()) return emptyList()
        val out = ArrayList<Fragment>(nalus.size)
        val lastNalIdx = nalus.lastIndex
        nalus.forEachIndexed { naluIdx, nalu ->
            val fragments = H264Fragmenter.fragment(nalu)
            val lastFragIdx = fragments.lastIndex
            fragments.forEachIndexed { fragIdx, frag ->
                val isLastOfFrame = naluIdx == lastNalIdx && fragIdx == lastFragIdx
                out += Fragment(frag.payload, end = isLastOfFrame)
            }
        }
        return out
    }
}
