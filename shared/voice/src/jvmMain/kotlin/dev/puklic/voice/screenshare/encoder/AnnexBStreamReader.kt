package dev.puklic.voice.screenshare.encoder

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Streaming H.264 Annex-B parser.
 *
 * Given an [InputStream] that contains a sequence of NAL units separated by start codes
 * (`0x00 00 01` or `0x00 00 00 01` — RFC 6184 §1.3, ITU-T H.264 Annex B), [asFlow] emits
 * one [ByteArray] per NAL **without** the start code prefix. Trailing zero bytes that are
 * part of the next start code are stripped from each emitted NAL.
 *
 * Unlike the byte-array-based [dev.puklic.voice.transport.AnnexBSplitter], this reader is
 * a true streaming parser: it does not buffer the whole stream and emits NALs as soon as
 * the next start code is detected. The final NAL is emitted on EOF.
 *
 * The reader is suspending only by virtue of the `flow { }` builder; the underlying I/O
 * is blocking. Collect on `Dispatchers.IO` (or use [kotlinx.coroutines.flow.flowOn]).
 */
internal class AnnexBStreamReader(private val input: InputStream) {

    fun asFlow(): Flow<ByteArray> = flow {
        val reader = BufferedInputStream(input, BUFFER_BYTES)
        val current = ByteArrayOutputStream(INITIAL_NAL_CAPACITY)
        var window = ROLLING_WINDOW_INIT // 24-bit rolling window of last 3 bytes
        var sawFirstStart = false

        while (true) {
            val b = reader.read()
            if (b == -1) break

            current.write(b)
            window = ((window shl 8) or (b and BYTE_MASK)) and WINDOW_MASK

            if (window == START_CODE_3) {
                // We've just consumed a 3-byte start code (00 00 01). Everything written
                // to `current` before these last 3 bytes belongs to the previous NAL.
                val buf = current.toByteArray()
                val nalEnd = buf.size - START_CODE_3_LEN
                if (sawFirstStart) {
                    // Detect 4-byte start code: previous byte (just before the 3-byte
                    // pattern) is also 0x00 → drop it from the previous NAL.
                    val endTrimmed = if (nalEnd > 0 && buf[nalEnd - 1] == ZERO_BYTE) nalEnd - 1 else nalEnd
                    if (endTrimmed > 0) emit(buf.copyOfRange(0, endTrimmed))
                }
                current.reset()
                sawFirstStart = true
                window = ROLLING_WINDOW_INIT
            }
        }

        // EOF — emit the last NAL (everything accumulated since the last start code).
        if (sawFirstStart && current.size() > 0) {
            emit(current.toByteArray())
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val INITIAL_NAL_CAPACITY = 8 * 1024
        const val START_CODE_3 = 0x000001
        const val START_CODE_3_LEN = 3
        const val WINDOW_MASK = 0xFFFFFF
        const val BYTE_MASK = 0xFF
        const val ROLLING_WINDOW_INIT = 0xFFFFFF // never matches start code on first byte
        const val ZERO_BYTE: Byte = 0
    }
}
