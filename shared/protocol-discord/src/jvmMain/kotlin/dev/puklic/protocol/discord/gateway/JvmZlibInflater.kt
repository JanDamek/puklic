package dev.puklic.protocol.discord.gateway

import java.util.zip.Inflater

/**
 * JVM implementation of [ZlibInflater] backed by `java.util.zip.Inflater`.
 *
 * Buffers incoming bytes, detects the Z_SYNC_FLUSH boundary (`00 00 FF FF`), inflates each
 * complete chunk through the same `Inflater` instance (continuous stream), and returns the
 * decoded UTF-8 strings.
 */
internal class JvmZlibInflater : ZlibInflater {
    private val inflater = Inflater()
    private val pending = mutableListOf<Byte>()

    override fun feed(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        for (b in bytes) pending.add(b)
        val out = mutableListOf<String>()
        while (true) {
            val end = findSyncFlush(pending) ?: break
            val chunk = ByteArray(end + SYNC_FLUSH.size) { pending[it] }
            // Drop the consumed prefix
            repeat(chunk.size) { pending.removeAt(0) }
            inflater.setInput(chunk)
            val buffer = ByteArray(DECOMPRESS_BUF)
            val sb = StringBuilder()
            while (!inflater.needsInput()) {
                val n = inflater.inflate(buffer)
                if (n == 0) break
                sb.append(String(buffer, 0, n, Charsets.UTF_8))
            }
            out.add(sb.toString())
        }
        return out
    }

    override fun reset() {
        inflater.reset()
        pending.clear()
    }

    private fun findSyncFlush(buf: List<Byte>): Int? {
        if (buf.size < SYNC_FLUSH.size) return null
        for (i in 0..buf.size - SYNC_FLUSH.size) {
            if (buf[i] == SYNC_FLUSH[0] &&
                buf[i + 1] == SYNC_FLUSH[1] &&
                buf[i + 2] == SYNC_FLUSH[2] &&
                buf[i + 3] == SYNC_FLUSH[3]
            ) {
                return i
            }
        }
        return null
    }

    private companion object {
        val SYNC_FLUSH: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        const val DECOMPRESS_BUF = 4096
    }
}
