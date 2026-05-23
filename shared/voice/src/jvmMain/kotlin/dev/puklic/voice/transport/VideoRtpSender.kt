package dev.puklic.voice.transport

import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes a video frame (Annex-B bytes) into a series of encrypted RTP packets and sends them via the UDP transport.
 *
 *  1. Split the frame into NAL units (Annex-B start codes stripped).
 *  2. For each NAL unit, run RFC 6184 §5.8 FU-A fragmentation against the safe MTU.
 *  3. All fragments of the frame share the same 90 kHz timestamp; only the LAST RTP packet of the frame carries the marker bit.
 *  4. Each fragment is encrypted with `aead_xchacha20_poly1305_rtpsize` (RTP header is AAD, nonce counter appended).
 *
 * Per architect report 2026-05-23-screenshare.md §5.
 */
internal class VideoRtpSender(
    private val udp: UdpRtpTransport,
    private val encryptor: AeadCipher,
    private val nonceGen: NonceGenerator,
    private val videoSsrc: Int,
    private val payloadType: Byte = RtpPacket.PAYLOAD_TYPE_H264,
) {
    private val sequence = AtomicInteger(0)

    suspend fun send(encodedFrame: EncodedFrame, timestamp90k: Int) {
        val nalus = AnnexBSplitter.split(encodedFrame.bytes)
        if (nalus.isEmpty()) return
        val perNalFragments = nalus.map { H264Fragmenter.fragment(it) }
        val lastNalIdx = perNalFragments.lastIndex
        perNalFragments.forEachIndexed { naluIdx, fragments ->
            val lastFragIdx = fragments.lastIndex
            fragments.forEachIndexed { fragIdx, fragment ->
                val isLastPacket = naluIdx == lastNalIdx && fragIdx == lastFragIdx
                val seq = (sequence.getAndIncrement() and 0xFFFF).toShort()
                val header = RtpPacket.writeHeader(seq, timestamp90k, videoSsrc, payloadType, marker = isLastPacket)
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
}
