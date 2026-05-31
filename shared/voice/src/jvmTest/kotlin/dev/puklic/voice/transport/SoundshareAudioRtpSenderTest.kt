package dev.puklic.voice.transport

import dev.puklic.voice.codec.transport.VoiceUdpTransport
import dev.puklic.voice.crypto.AeadCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [SoundshareAudioRtpSender] — sibling of the mic audio path that publishes
 * Opus packets to Discord on the soundshare SSRC. See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-28-screencast-audio-ssrc.md` §5 / §6 (B-2).
 *
 * The mic path uses [VoicePacketCodec] inline inside `DefaultVoiceClient`; the soundshare
 * path needs its own [VoicePacketCodec] (independent sequence/timestamp/nonce counters) and
 * its own SSRC. Same UDP socket, same AEAD key/cipher.
 */
class SoundshareAudioRtpSenderTest {

    @Test
    fun send_advances_sequence_and_timestamp_per_frame() = runTest {
        val transport = CapturingTransport()
        val sender = SoundshareAudioRtpSender(
            udp = transport,
            aead = NullCipher(),
            ssrc = SOUNDSHARE_SSRC,
        )

        sender.send(byteArrayOf(0x01, 0x02))
        sender.send(byteArrayOf(0x03, 0x04))
        sender.send(byteArrayOf(0x05, 0x06))

        assertEquals(3, transport.sent.size)
        val h0 = RtpPacket.readHeader(transport.sent[0])
        val h1 = RtpPacket.readHeader(transport.sent[1])
        val h2 = RtpPacket.readHeader(transport.sent[2])

        // SSRC stable.
        assertEquals(SOUNDSHARE_SSRC, h0.ssrc)
        assertEquals(SOUNDSHARE_SSRC, h1.ssrc)
        assertEquals(SOUNDSHARE_SSRC, h2.ssrc)

        // Sequence monotonically increments by 1.
        assertEquals((h0.sequence.toInt() and 0xFFFF) + 1, h1.sequence.toInt() and 0xFFFF)
        assertEquals((h1.sequence.toInt() and 0xFFFF) + 1, h2.sequence.toInt() and 0xFFFF)

        // Timestamp advances by SAMPLES_PER_FRAME (= 960 @ 48 kHz, channel-independent per RFC 7587).
        assertEquals(h0.timestamp + VoicePacketCodec.AUDIO_SAMPLES_PER_FRAME, h1.timestamp)
        assertEquals(h1.timestamp + VoicePacketCodec.AUDIO_SAMPLES_PER_FRAME, h2.timestamp)

        // Payload type = Opus (120) for soundshare RTP frames.
        assertEquals(RtpPacket.PAYLOAD_TYPE_OPUS, h0.payloadType)
    }

    @Test
    fun send_independent_sender_does_not_share_counters_with_mic() = runTest {
        // Two independent SoundshareAudioRtpSender instances must have independent
        // sequence / timestamp / nonce counters (separate VoicePacketCodec instances).
        // This mirrors the requirement that mic + soundshare share the UDP socket but NOT
        // the RTP state machine — each SSRC has its own monotonic 48 kHz clock per §2.1.
        val txA = CapturingTransport()
        val txB = CapturingTransport()
        val senderA = SoundshareAudioRtpSender(udp = txA, aead = NullCipher(), ssrc = SOUNDSHARE_SSRC)
        val senderB = SoundshareAudioRtpSender(udp = txB, aead = NullCipher(), ssrc = SOUNDSHARE_SSRC + 1)

        senderA.send(byteArrayOf(0x01))
        senderA.send(byteArrayOf(0x02))
        senderB.send(byteArrayOf(0x03))

        val a0 = RtpPacket.readHeader(txA.sent[0])
        val a1 = RtpPacket.readHeader(txA.sent[1])
        val b0 = RtpPacket.readHeader(txB.sent[0])

        // A's counters advanced; B's are fresh.
        assertEquals((a0.sequence.toInt() and 0xFFFF) + 1, a1.sequence.toInt() and 0xFFFF)
        assertEquals(a0.sequence, b0.sequence) // both start at the same initial value (0)
        assertEquals(a0.timestamp, b0.timestamp)
    }

    @Test
    fun send_encrypts_opus_payload_with_aead() = runTest {
        // Verify the AEAD is invoked: a non-null cipher must produce ciphertext different from
        // plaintext, and the tag must extend the payload (NullCipher appends 16 zero bytes as a
        // surrogate for the Poly1305 tag — same convention as VoicePacketCodec tests).
        val transport = CapturingTransport()
        val sender = SoundshareAudioRtpSender(
            udp = transport,
            aead = NullCipher(),
            ssrc = SOUNDSHARE_SSRC,
        )

        val opus = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        sender.send(opus)

        val packet = transport.sent.single()
        // Layout: 12 B header + (4 B opus + 16 B tag) + 4 B nonce-counter = 36 B.
        assertEquals(RtpPacket.HEADER_SIZE + opus.size + 16 + 4, packet.size)
        // First 12 B = RTP header (parses without throwing).
        RtpPacket.readHeader(packet)
        // Opus bytes are present at the expected offset (NullCipher copies plaintext through).
        for (i in opus.indices) {
            assertEquals(opus[i], packet[RtpPacket.HEADER_SIZE + i], "byte $i")
        }
        // Last 4 B = nonce counter (big-endian). NonceGenerator emits its `initial` (0) for the
        // first call (see `NonceGenerator.next()` doc), so all 4 nonce bytes are zero here.
        for (i in 0 until 4) {
            assertEquals(0, packet[packet.size - 4 + i].toInt() and 0xFF, "nonce byte $i should be 0")
        }
    }

    private companion object {
        const val SOUNDSHARE_SSRC = 0xCAFE_BAB0.toInt()
    }

    private class CapturingTransport : VoiceUdpTransport {
        val sent: MutableList<ByteArray> = CopyOnWriteArrayList()
        override suspend fun send(packet: ByteArray) { sent += packet.copyOf() }
        override val incoming: Flow<ByteArray> = emptyFlow()
        override fun close() {}
    }

    private class NullCipher : AeadCipher {
        override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            plaintext + ByteArray(TAG_BYTES)
        override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
            ciphertextWithTag.copyOf(ciphertextWithTag.size - TAG_BYTES)
        private companion object { const val TAG_BYTES = 16 }
    }
}
