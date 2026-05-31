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

class VideoRtpSenderTest {

    @Test
    fun `marker bit set only on last packet of frame across multiple NALUs`() = runTest {
        val transport = CapturingTransport()
        val sender = VideoRtpSender(
            udp = transport,
            encryptor = xchacha20Poly1305(ByteArray(32)),
            nonceGen = NonceGenerator(0),
            videoSsrc = 0x42,
        )
        // Two NALUs separated by 4-byte Annex-B start codes; both small (single-fragment each).
        val nal1 = ByteArray(100) { 0x11 }.also { it[0] = 0x67.toByte() }
        val nal2 = ByteArray(100) { 0x22 }.also { it[0] = 0x65.toByte() }
        val frame = byteArrayOf(0, 0, 0, 1) + nal1 + byteArrayOf(0, 0, 0, 1) + nal2

        sender.send(EncodedFrame(frame, ts90k = 3000, keyframe = true), timestamp90k = 3000)

        transport.sent.size shouldBe 2
        // Decode RTP headers
        val h0 = RtpPacket.readHeader(transport.sent[0])
        val h1 = RtpPacket.readHeader(transport.sent[1])
        h0.marker shouldBe false
        h1.marker shouldBe true
        h0.sequence shouldBe 0.toShort()
        h1.sequence shouldBe 1.toShort()
        h0.timestamp shouldBe 3000
        h1.timestamp shouldBe 3000
        h0.payloadType shouldBe RtpPacket.PAYLOAD_TYPE_H264
    }

    @Test
    fun `sequence numbers increment monotonically across fragments`() = runTest {
        val transport = CapturingTransport()
        val sender = VideoRtpSender(
            udp = transport,
            encryptor = xchacha20Poly1305(ByteArray(32)),
            nonceGen = NonceGenerator(0),
            videoSsrc = 0x99,
        )
        // Force fragmentation with a 5000-byte NAL.
        val largeNal = ByteArray(5000) { (it and 0xFF).toByte() }.also { it[0] = 0x65.toByte() }
        val frame = byteArrayOf(0, 0, 0, 1) + largeNal
        sender.send(EncodedFrame(frame, ts90k = 6000, keyframe = true), timestamp90k = 6000)

        (transport.sent.size >= 2) shouldBe true
        transport.sent.forEachIndexed { i, pkt ->
            val h = RtpPacket.readHeader(pkt)
            h.sequence shouldBe i.toShort()
            h.timestamp shouldBe 6000
        }
        // Only the last packet should carry the marker bit.
        val lastIdx = transport.sent.lastIndex
        transport.sent.forEachIndexed { i, pkt ->
            RtpPacket.readHeader(pkt).marker shouldBe (i == lastIdx)
        }
    }

    private class CapturingTransport : VoiceUdpTransport {
        val sent: MutableList<ByteArray> = CopyOnWriteArrayList()
        override suspend fun send(packet: ByteArray) { sent += packet.copyOf() }
        override val incoming: Flow<ByteArray> = emptyFlow()
        override fun close() {}
    }
}
