package dev.puklic.voice.dave.gateway

/**
 * Binary frame layout for DAVE opcodes 25-30 on the voice-gateway WS binary
 * channel:
 *
 * ```
 *   offset  size  field
 *   ------  ----  --------------
 *   0       2     seq:u16 BE
 *   2       1     op:u8
 *   3       ...   MLS payload bytes
 * ```
 *
 * Source: architect report `2026-05-23-dave-e2ee.md` §5.
 */
internal object DaveBinaryFrame {

    /** Smallest valid frame = 2 B seq + 1 B op + (>=0 B payload). */
    private const val HEADER_LEN: Int = 3

    /**
     * Parse a binary frame. Returns null if [frame] is shorter than the header
     * (caller treats null as "discard / log"); a zero-byte payload is allowed.
     */
    fun parse(frame: ByteArray): Parsed? {
        if (frame.size < HEADER_LEN) return null
        val seq = ((frame[0].toInt() and 0xFF) shl Byte.SIZE_BITS) or (frame[1].toInt() and 0xFF)
        val op = frame[2].toInt() and 0xFF
        val payload = frame.copyOfRange(HEADER_LEN, frame.size)
        return Parsed(seq.toUShort(), op, payload)
    }

    /**
     * Build a binary frame. Mutates nothing; returns a new byte array of size
     * `3 + payload.size`.
     */
    fun write(seq: UShort, op: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = ((seq.toInt() shr Byte.SIZE_BITS) and 0xFF).toByte()
        out[1] = (seq.toInt() and 0xFF).toByte()
        out[2] = (op and 0xFF).toByte()
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    data class Parsed(val seq: UShort, val op: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Parsed && seq == other.seq && op == other.op && payload.contentEquals(other.payload)

        override fun hashCode(): Int {
            var h = seq.hashCode()
            h = HASH_MIXER * h + op
            h = HASH_MIXER * h + payload.contentHashCode()
            return h
        }
    }

    private const val HASH_MIXER = 31
}
