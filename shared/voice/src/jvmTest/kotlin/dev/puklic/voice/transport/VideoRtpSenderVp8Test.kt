package dev.puklic.voice.transport

import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.NonceGenerator
import dev.puklic.voice.crypto.xchacha20Poly1305
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/**
 * VP8 end-to-end round-trip test: a VP8 [EncodedFrame] passed through [VideoRtpSender] with the
 * [Vp8Packetiser] strategy must:
 *
 *  - produce RTP packets stamped with `PAYLOAD_TYPE_VP8 = 0x67`
 *  - prefix each payload with the RFC 7741 single-octet descriptor (S=1 on first, S=0 after)
 *  - set the marker bit only on the last packet of the frame
 *  - increment sequence numbers monotonically across fragments
 */
class VideoRtpSenderVp8Test {

    @Test
    fun `small VP8 frame is sent as one packet with VP8 payload type and marker`() = runTest {
        val transport = CapturingTransport()
        val sender = VideoRtpSender(
            udp = transport,
            encryptor = xchacha20Poly1305(ByteArray(32)),
            nonceGen = NonceGenerator(0),
            videoSsrc = 0x42,
            payloadType = RtpPacket.PAYLOAD_TYPE_VP8,
            fragmenter = Vp8Packetiser,
        )

        val payload = ByteArray(800) { (it and 0xFF).toByte() }
        sender.send(EncodedFrame(payload, ts90k = 9000, keyframe = true), timestamp90k = 9000)

        transport.sent.size shouldBe 1
        val header = RtpPacket.readHeader(transport.sent[0])
        header.payloadType shouldBe RtpPacket.PAYLOAD_TYPE_VP8
        header.marker shouldBe true
        header.timestamp shouldBe 9000
        header.sequence shouldBe 0.toShort()
    }

    @Test
    fun `large VP8 frame fragments carry monotonic sequence and final marker bit`() = runTest {
        val transport = CapturingTransport()
        val sender = VideoRtpSender(
            udp = transport,
            encryptor = xchacha20Poly1305(ByteArray(32)),
            nonceGen = NonceGenerator(0),
            videoSsrc = 0x99,
            payloadType = RtpPacket.PAYLOAD_TYPE_VP8,
            fragmenter = Vp8Packetiser,
        )

        val payload = ByteArray(5000) { (it and 0xFF).toByte() }
        sender.send(EncodedFrame(payload, ts90k = 12000, keyframe = true), timestamp90k = 12000)

        (transport.sent.size >= 2) shouldBe true
        val lastIdx = transport.sent.lastIndex
        transport.sent.forEachIndexed { i, pkt ->
            val h = RtpPacket.readHeader(pkt)
            h.payloadType shouldBe RtpPacket.PAYLOAD_TYPE_VP8
            h.sequence shouldBe i.toShort()
            h.timestamp shouldBe 12000
            h.marker shouldBe (i == lastIdx)
        }
    }

    private class CapturingTransport : VoiceUdpTransport {
        val sent: MutableList<ByteArray> = CopyOnWriteArrayList()
        override suspend fun send(packet: ByteArray) { sent += packet.copyOf() }
        override val incoming: Flow<ByteArray> = emptyFlow()
        override fun close() {}
    }
}
