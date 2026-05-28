package dev.puklic.voice.audio

/**
 * One Opus-encoded audio packet ready for RTP packetisation.
 *
 * Emitted by screencast audio readers (Linux PipeWire, macOS BlackHole). The downstream
 * RTP sender (wired in once the Discord soundshare SSRC model lands — see issue #25
 * prerequisite 1) consumes this Flow, wraps each packet in an RTP packet with the
 * negotiated soundshare SSRC, encrypts it via the active [dev.puklic.voice.crypto.AeadCipher],
 * and sends it through the shared UDP transport.
 *
 * @property opus Opus packet bytes (Discord profile: 48 kHz, 20 ms frame, stereo for
 *  screencast audio). Never longer than `AudioConstants.MAX_OPUS_FRAME_BYTES`.
 * @property rtpTimestamp Opus 48 kHz RTP timestamp (per RFC 7587 §4.2 — Opus always uses
 *  a 48 kHz clock regardless of internal encoder sample rate). Monotonically increasing
 *  by `SAMPLES_PER_FRAME` (960) per 20 ms packet from a session-arbitrary start value.
 */
public data class EncodedAudioPacket(
    val opus: ByteArray,
    val rtpTimestamp: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedAudioPacket) return false
        return rtpTimestamp == other.rtpTimestamp && opus.contentEquals(other.opus)
    }

    override fun hashCode(): Int = 31 * opus.contentHashCode() + rtpTimestamp.hashCode()
}
