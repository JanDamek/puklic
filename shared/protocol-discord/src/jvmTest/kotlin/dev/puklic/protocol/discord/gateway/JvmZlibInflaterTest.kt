package dev.puklic.protocol.discord.gateway

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmZlibInflaterTest {
    @Test
    fun decompresses_single_sync_flush_frame() {
        val payload = """{"op":11}"""
        val compressed = compressSyncFlush(payload)
        val inflater = JvmZlibInflater()
        val out = inflater.feed(compressed)
        assertEquals(1, out.size)
        assertEquals(payload, out[0])
    }

    @Test
    fun returns_empty_when_no_sync_flush_yet() {
        val inflater = JvmZlibInflater()
        // Random bytes without a sync flush boundary
        assertTrue(inflater.feed(byteArrayOf(0x78, 0x9C.toByte(), 0x01, 0x02, 0x03)).isEmpty())
    }

    @Test
    fun decompresses_two_concatenated_frames() {
        val a = """{"op":10,"d":{"heartbeat_interval":45000}}"""
        val b = """{"op":11}"""
        val deflater = Deflater()
        val first = deflateChunk(deflater, a)
        val second = deflateChunk(deflater, b)
        deflater.end()
        val inflater = JvmZlibInflater()
        val combined = first + second
        val out = inflater.feed(combined)
        assertEquals(listOf(a, b), out)
    }

    private fun compressSyncFlush(text: String): ByteArray {
        val d = Deflater()
        return try {
            deflateChunk(d, text)
        } finally {
            d.end()
        }
    }

    private fun deflateChunk(deflater: Deflater, text: String): ByteArray {
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        val buf = ByteArray(BUF_SIZE)
        val out = mutableListOf<Byte>()
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            if (n == 0) break
            for (i in 0 until n) out.add(buf[i])
        }
        return ByteArray(out.size) { out[it] }
    }

    private companion object {
        const val BUF_SIZE = 4096
    }
}
