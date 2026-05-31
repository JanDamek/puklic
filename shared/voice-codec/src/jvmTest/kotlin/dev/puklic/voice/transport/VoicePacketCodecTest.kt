package dev.puklic.voice.transport

import dev.puklic.voice.crypto.xchacha20Poly1305
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random
import kotlin.test.Test

class VoicePacketCodecTest {

    @Test
    fun `encode then decode round-trips Opus payload and header fields`() {
        val key = Random(1).nextBytes(32)
        val ssrc = 0x12345678
        val codec = VoicePacketCodec(xchacha20Poly1305(key), ssrc)
        val opus = Random(2).nextBytes(120)

        val packet = codec.encode(opus)
        val decoded = VoicePacketCodec(xchacha20Poly1305(key), ssrc).decode(packet)

        decoded.opus.toList() shouldBe opus.toList()
        decoded.header.ssrc shouldBe ssrc
        decoded.header.sequence shouldBe 0.toShort()
        decoded.header.timestamp shouldBe 0
    }

    @Test
    fun `sequence and timestamp advance per encode`() {
        val codec = VoicePacketCodec(xchacha20Poly1305(ByteArray(32)), ssrc = 7)
        val p1 = codec.encode(byteArrayOf(1, 2, 3))
        val p2 = codec.encode(byteArrayOf(4, 5, 6))

        val h1 = RtpPacket.readHeader(p1)
        val h2 = RtpPacket.readHeader(p2)
        h1.sequence shouldBe 0.toShort()
        h2.sequence shouldBe 1.toShort()
        h1.timestamp shouldBe 0
        h2.timestamp shouldBe VoicePacketCodec.AUDIO_SAMPLES_PER_FRAME
    }

    @Test
    fun `successive encodes produce different ciphertexts due to nonce counter`() {
        val key = Random(5).nextBytes(32)
        val codec = VoicePacketCodec(xchacha20Poly1305(key), ssrc = 42)
        val payload = ByteArray(80) { 0x55 }
        val p1 = codec.encode(payload)
        val p2 = codec.encode(payload)
        // Strip the 12-byte header (sequence/timestamp differ) and compare ciphertext-body+nonce-tail.
        val c1 = p1.copyOfRange(RtpPacket.HEADER_SIZE, p1.size)
        val c2 = p2.copyOfRange(RtpPacket.HEADER_SIZE, p2.size)
        c1.toList() shouldNotBe c2.toList()
    }

    @Test
    fun `packet wire layout is header(12) + ciphertext+tag + nonce(4)`() {
        val codec = VoicePacketCodec(xchacha20Poly1305(ByteArray(32)), ssrc = 1)
        val payload = ByteArray(50)
        val pkt = codec.encode(payload)
        // 12 header + (50 plaintext + 16 tag) + 4 nonce counter = 82
        pkt.size shouldBe (12 + 50 + 16 + 4)
        // Nonce counter trailer for first packet = 0 (big-endian)
        pkt[pkt.size - 4] shouldBe 0
        pkt[pkt.size - 3] shouldBe 0
        pkt[pkt.size - 2] shouldBe 0
        pkt[pkt.size - 1] shouldBe 0
    }
}
