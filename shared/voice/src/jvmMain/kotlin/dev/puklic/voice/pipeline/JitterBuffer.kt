package dev.puklic.voice.pipeline

/**
 * Tiny reorder + gap-detection ring buffer for one SSRC's Opus packets.
 *
 * Push packets as they arrive (out of order is fine within `maxSlots`); drain returns
 * the next contiguous run of frames in sequence order. A missing slot becomes `null`
 * once the buffer has moved past it — the caller is expected to feed `null` to the
 * Opus decoder which performs packet-loss concealment (PLC).
 *
 * Discord voice packets normally arrive in order at ~50 Hz, so this is best-effort
 * jitter smoothing, not a full RFC-3550 reordering buffer. Per architect report
 * `2026-05-23-voice.md` §9.
 *
 * Sequence numbers are 16-bit RTP sequence numbers (wrap around 65536). Comparison
 * uses serial-number arithmetic (RFC 1982) within a ± 0x8000 window.
 */
internal class JitterBuffer(
    private val targetMs: Int = 40,
    private val maxSlots: Int = 8,
) {

    private val slots = arrayOfNulls<ByteArray>(maxSlots)
    private val present = BooleanArray(maxSlots)
    private var expectedSeq: Int = -1

    /** Push one packet; returns a (possibly empty) list of frames ready to decode in order.
     *  Each element is the raw Opus payload, or `null` to signal a lost frame (PLC). */
    fun push(seq: Short, opus: ByteArray): List<ByteArray?> {
        val seqU = seq.toInt() and 0xFFFF
        if (expectedSeq < 0) {
            expectedSeq = seqU
            return placeAndDrain(seqU, opus)
        }
        val delta = seqDelta(seqU, expectedSeq)
        // Packet is older than our window — drop silently.
        if (delta < 0) return emptyList()
        // Packet is so far ahead it would wrap the ring — reset and start from here.
        if (delta >= maxSlots) {
            reset()
            expectedSeq = seqU
            return placeAndDrain(seqU, opus)
        }
        return placeAndDrain(seqU, opus)
    }

    fun reset() {
        for (i in 0 until maxSlots) {
            slots[i] = null
            present[i] = false
        }
        expectedSeq = -1
    }

    private fun placeAndDrain(seqU: Int, opus: ByteArray): List<ByteArray?> {
        val idx = seqU % maxSlots
        slots[idx] = opus
        present[idx] = true
        return drain()
    }

    /**
     * Drain contiguous frames from `expectedSeq`. When `expectedSeq` slot is missing
     * AND any later slot within the window is present, we emit `null` (PLC) and advance.
     * If no later slot is present, we wait for more packets.
     */
    private fun drain(): List<ByteArray?> {
        val out = mutableListOf<ByteArray?>()
        while (true) {
            val idx = expectedSeq % maxSlots
            if (present[idx]) {
                out += slots[idx]
                slots[idx] = null
                present[idx] = false
                expectedSeq = (expectedSeq + 1) and 0xFFFF
                continue
            }
            // Slot missing: emit PLC only if some later slot in the window is filled,
            // otherwise wait (the packet may still arrive).
            if (!hasLaterPresent()) break
            out += null
            expectedSeq = (expectedSeq + 1) and 0xFFFF
        }
        return out
    }

    private fun hasLaterPresent(): Boolean {
        // Emit PLC only after a packet at least [PLC_GAP_AHEAD] slots beyond expectedSeq
        // is present — gives the missing frame one chance to arrive late.
        for (i in PLC_GAP_AHEAD until maxSlots) {
            val s = (expectedSeq + i) and 0xFFFF
            if (present[s % maxSlots]) return true
        }
        return false
    }

    private companion object {
        /** Minimum lead (slots ahead of expectedSeq) before we declare a missing frame lost. */
        const val PLC_GAP_AHEAD: Int = 2
    }

    private fun seqDelta(a: Int, b: Int): Int {
        // Serial-number arithmetic for 16-bit sequence numbers (RFC 1982).
        val diff = ((a - b) and 0xFFFF)
        return if (diff < 0x8000) diff else diff - 0x10000
    }
}
