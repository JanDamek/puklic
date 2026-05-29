package dev.puklic.voice.transport

import dev.puklic.voice.crypto.AeadCipher
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * KMP round-trip test for [VoicePacketCodec] using a fake [AeadCipher] that
 * passes the plaintext through with a fixed 16-byte zero tag. This verifies
 * the RTP header framing, nonce-counter trailer layout, and sequence /
 * timestamp progression without depending on a real cipher (which is JVM-only
 * today; CryptoKit / cinterop actuals land in FP-4..6).
 */
class VoicePacketCodecCommonTest {

    private class FakeAead : AeadCipher {
        private val tag = ByteArray(TAG_BYTES)
        override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            plaintext + tag
        override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - TAG_BYTES)

        companion object {
            const val TAG_BYTES: Int = 16
        }
    }

    @Test
    fun encode_then_decode_round_trips_payload_and_header_fields() {
        val ssrc = 0x12345678
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        val encoder = VoicePacketCodec(FakeAead(), ssrc)
        val packet = encoder.encode(payload)

        val decoder = VoicePacketCodec(FakeAead(), ssrc)
        val decoded = decoder.decode(packet)

        decoded.opus.toList() shouldBe payload.toList()
        decoded.header.ssrc shouldBe ssrc
        decoded.header.sequence shouldBe 0.toShort()
        decoded.header.timestamp shouldBe 0
    }

    @Test
    fun sequence_and_timestamp_advance_per_encode() {
        val codec = VoicePacketCodec(FakeAead(), ssrc = 7)
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
    fun packet_wire_layout_is_header_then_ciphertext_then_nonce_counter() {
        val codec = VoicePacketCodec(FakeAead(), ssrc = 1)
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
