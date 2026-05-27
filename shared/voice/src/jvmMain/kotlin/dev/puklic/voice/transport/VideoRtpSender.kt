package dev.puklic.voice.transport

import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes a video frame into a series of encrypted RTP packets and sends them via the UDP transport.
 *
 *  1. Delegate codec-specific packetisation to a [VideoFrameFragmenter] strategy:
 *     - H.264 → [H264FrameFragmenter] (RFC 6184 §5.8 FU-A on Annex-B NAL units).
 *     - VP8 → [Vp8Packetiser] (RFC 7741 §4 single-octet descriptor).
 *  2. All fragments share the same 90 kHz timestamp; the RTP marker bit is set only on the
 *     fragment whose `end = true` (the LAST RTP packet of the frame).
 *  3. Each fragment is encrypted with `aead_xchacha20_poly1305_rtpsize` — RTP header is AAD,
 *     nonce counter appended.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §5.
 */
internal class VideoRtpSender(
    private val udp: UdpRtpTransport,
    private val encryptor: AeadCipher,
    private val nonceGen: NonceGenerator,
    private val videoSsrc: Int,
    private val payloadType: Byte = RtpPacket.PAYLOAD_TYPE_H264,
    private val fragmenter: VideoFrameFragmenter = H264FrameFragmenter,
) {
    private val sequence = AtomicInteger(0)

    suspend fun send(encodedFrame: EncodedFrame, timestamp90k: Int) {
        val fragments = fragmenter.fragment(encodedFrame)
        if (fragments.isEmpty()) return
        fragments.forEach { fragment ->
            val seq = (sequence.getAndIncrement() and 0xFFFF).toShort()
            val header = RtpPacket.writeHeader(seq, timestamp90k, videoSsrc, payloadType, marker = fragment.end)
            val counter = nonceGen.next()
            val nonce24 = ByteArray(VoicePacketCodec.NONCE_SIZE).also {
                VoicePacketCodec.writeIntBE(it, 0, counter)
            }
            val ciphertext = encryptor.encrypt(fragment.payload, nonce24, header)
            val packet = ByteArray(RtpPacket.HEADER_SIZE + ciphertext.size + VoicePacketCodec.NONCE_COUNTER_SIZE)
            System.arraycopy(header, 0, packet, 0, RtpPacket.HEADER_SIZE)
            System.arraycopy(ciphertext, 0, packet, RtpPacket.HEADER_SIZE, ciphertext.size)
            VoicePacketCodec.writeIntBE(packet, packet.size - VoicePacketCodec.NONCE_COUNTER_SIZE, counter)
            udp.send(packet)
        }
    }
}
