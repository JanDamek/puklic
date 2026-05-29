package dev.puklic.voice.transport

import dev.puklic.voice.crypto.AeadCipher
import dev.puklic.voice.crypto.NonceGenerator
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Encodes / decodes a full Discord voice RTP packet with `aead_xchacha20_poly1305_rtpsize`:
 *
 *   `RTP-header(12) || ciphertext+tag || nonce-counter(4)`
 *
 * Per architect report 2026-05-23-voice.md §6–§7. Timestamp advances by
 * [AUDIO_SAMPLES_PER_FRAME] per encode (20 ms @ 48 kHz mono).
 *
 * KMP — backed by the Kotlin stdlib KMP atomic (`kotlin.concurrent.atomics`).
 */
@OptIn(ExperimentalAtomicApi::class)
public class VoicePacketCodec(
    private val aead: AeadCipher,
    private val ssrc: Int,
    initialSequence: Int = 0,
    initialTimestamp: Int = 0,
    initialNonce: Int = 0,
) {

    private val sequence = AtomicInt(initialSequence)
    private val timestamp = AtomicInt(initialTimestamp)
    private val nonceCounter = NonceGenerator(initialNonce)

    public fun encode(opusFrame: ByteArray): ByteArray {
        val seq = (sequence.fetchAndAdd(1) and 0xFFFF).toShort()
        val ts = timestamp.fetchAndAdd(AUDIO_SAMPLES_PER_FRAME)
        val header = RtpPacket.writeHeader(seq, ts, ssrc)
        val counter = nonceCounter.next()
        val nonce24 = buildNonce(counter)
        val ciphertext = aead.encrypt(opusFrame, nonce24, header)
        val packet = ByteArray(RtpPacket.HEADER_SIZE + ciphertext.size + NONCE_COUNTER_SIZE)
        header.copyInto(packet, destinationOffset = 0)
        ciphertext.copyInto(packet, destinationOffset = RtpPacket.HEADER_SIZE)
        writeIntBE(packet, packet.size - NONCE_COUNTER_SIZE, counter)
        return packet
    }

    public fun decode(packet: ByteArray): Decoded {
        require(packet.size > RtpPacket.HEADER_SIZE + NONCE_COUNTER_SIZE) {
            "Voice packet too short: ${packet.size}"
        }
        val header = RtpPacket.readHeader(packet)
        val aad = packet.copyOfRange(0, RtpPacket.HEADER_SIZE)
        val counter = readIntBE(packet, packet.size - NONCE_COUNTER_SIZE)
        val nonce24 = buildNonce(counter)
        val ciphertext = packet.copyOfRange(RtpPacket.HEADER_SIZE, packet.size - NONCE_COUNTER_SIZE)
        val opus = aead.decrypt(ciphertext, nonce24, aad)
        return Decoded(header, opus)
    }

    public data class Decoded(val header: RtpPacket.Header, val opus: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return header == other.header && opus.contentEquals(other.opus)
        }
        override fun hashCode(): Int = 31 * header.hashCode() + opus.contentHashCode()
    }

    public companion object {
        public const val NONCE_COUNTER_SIZE: Int = 4
        public const val NONCE_SIZE: Int = 24
        public const val AUDIO_SAMPLES_PER_FRAME: Int = 960

        public fun buildNonce(counter: Int): ByteArray =
            ByteArray(NONCE_SIZE).also { writeIntBE(it, 0, counter) }

        public fun writeIntBE(dst: ByteArray, off: Int, v: Int) {
            dst[off] = ((v ushr 24) and 0xFF).toByte()
            dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
            dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
            dst[off + 3] = (v and 0xFF).toByte()
        }

        public fun readIntBE(src: ByteArray, off: Int): Int =
            ((src[off].toInt() and 0xFF) shl 24) or
                ((src[off + 1].toInt() and 0xFF) shl 16) or
                ((src[off + 2].toInt() and 0xFF) shl 8) or
                (src[off + 3].toInt() and 0xFF)
    }
}
