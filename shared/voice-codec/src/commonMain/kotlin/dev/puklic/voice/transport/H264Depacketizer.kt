package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * Inverse of [H264Fragmenter]. Reassembles incoming RTP H.264 payloads (RFC 6184) back into
 * Annex-B framed access units.
 *
 * Supported payload kinds:
 *  - Single NAL unit (type 1..23) — emitted as-is.
 *  - STAP-A (type 24) — splits the aggregation packet back into individual NAL units.
 *  - FU-A (type 28) — accumulates fragments between the S and E flags into one NAL.
 *
 * Unsupported / dropped: STAP-B (25), MTAP16/24 (26/27), FU-B (29). Discord screenshare uses
 * only Single + STAP-A + FU-A in practice.
 *
 * Access-unit boundaries are signalled by the RTP marker bit on the *last* packet of the
 * frame. When [push] sees marker=true it returns a freshly-built Annex-B byte array
 * (`00 00 00 01` start code prefix + NAL bytes, concatenated for every accumulated NAL).
 *
 * Caller is expected to feed packets per remote SSRC (one depacketizer per SSRC).
 */
@PuklicVoiceCodec
public class H264Depacketizer {

    private val pendingNals: MutableList<ByteArray> = mutableListOf()
    private var fuBuffer: ByteArray = ByteArray(0)
    private var inFu: Boolean = false

    /**
     * Feed one RTP payload (without RTP header). Returns the assembled Annex-B access unit
     * if [marker] is true and at least one NAL has accumulated, else null.
     *
     * Sequence ordering / loss handling is intentionally minimal: out-of-order or lost
     * fragments inside an FU-A are dropped (FU buffer reset on next S=1). The caller's
     * jitter strategy is delegated to RTP-sequence ordering at the dispatcher level if
     * needed; for screenshare receive v1 we tolerate occasional frame loss.
     */
    fun push(payload: ByteArray, marker: Boolean): ByteArray? {
        if (payload.isEmpty()) return finishIfMarker(marker)
        val nalHeader = payload[0].toInt() and 0xFF
        val nalUnitType = nalHeader and 0x1F
        when (nalUnitType) {
            in 1..23 -> {
                // Single NAL — copy of the entire payload IS the NAL unit.
                pendingNals += payload.copyOf()
            }
            STAP_A -> handleStapA(payload)
            FU_A -> handleFuA(payload)
            else -> {
                // Unsupported / drop. Keep going so we still emit the marker frame.
            }
        }
        return finishIfMarker(marker)
    }

    private fun handleStapA(payload: ByteArray) {
        var i = 1
        while (i + STAP_A_SIZE_PREFIX <= payload.size) {
            val nalSize = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
            i += STAP_A_SIZE_PREFIX
            if (nalSize <= 0 || i + nalSize > payload.size) break
            pendingNals += payload.copyOfRange(i, i + nalSize)
            i += nalSize
        }
    }

    private fun handleFuA(payload: ByteArray) {
        if (payload.size < FU_A_HEADER_SIZE) return
        val indicator = payload[0].toInt() and 0xFF
        val fuHeader = payload[1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val origType = fuHeader and 0x1F
        if (start) {
            // Reconstruct the original NAL header from FU-A indicator NRI + FU header type bits.
            val nri = indicator and 0x60
            val reconstructed = (nri or origType).toByte()
            fuBuffer = ByteArray(payload.size - FU_A_HEADER_SIZE + 1)
            fuBuffer[0] = reconstructed
            payload.copyInto(fuBuffer, destinationOffset = 1, startIndex = FU_A_HEADER_SIZE)
            inFu = true
        } else if (inFu) {
            val frag = payload.copyOfRange(FU_A_HEADER_SIZE, payload.size)
            fuBuffer = fuBuffer + frag
        } else {
            // Mid/end fragment without a Start — drop the whole FU.
            return
        }
        if (end && inFu) {
            pendingNals += fuBuffer
            fuBuffer = ByteArray(0)
            inFu = false
        }
    }

    private fun finishIfMarker(marker: Boolean): ByteArray? {
        if (!marker) return null
        if (pendingNals.isEmpty()) return null
        var total = 0
        for (nal in pendingNals) total += START_CODE.size + nal.size
        val out = ByteArray(total)
        var off = 0
        for (nal in pendingNals) {
            START_CODE.copyInto(out, destinationOffset = off)
            off += START_CODE.size
            nal.copyInto(out, destinationOffset = off)
            off += nal.size
        }
        pendingNals.clear()
        // FU partial buffer is intentionally NOT flushed on marker — a marker mid-FU is malformed
        // and we'd rather drop the partial NAL than emit garbage.
        return out
    }

    companion object {
        public const val STAP_A: Int = 24
        public const val FU_A: Int = 28
        public const val FU_A_HEADER_SIZE: Int = 2 // indicator + FU header
        public const val STAP_A_SIZE_PREFIX: Int = 2
        public val START_CODE: ByteArray = byteArrayOf(0, 0, 0, 1)
    }
}
