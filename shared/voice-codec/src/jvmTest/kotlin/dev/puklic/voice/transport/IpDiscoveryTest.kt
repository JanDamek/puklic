package dev.puklic.voice.transport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test

class IpDiscoveryTest {

    @Test
    fun `buildRequest produces 74-byte packet with type 0x0001 length 70 and ssrc`() {
        val pkt = IpDiscovery.buildRequest(ssrc = 12345)
        pkt.size shouldBe 74
        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        buf.short.toInt() and 0xFFFF shouldBe 0x0001
        buf.short.toInt() and 0xFFFF shouldBe 70
        buf.int shouldBe 12345
        // address 64 bytes all zero
        val addr = ByteArray(64)
        buf.get(addr)
        addr.all { it == 0.toByte() } shouldBe true
        // port 2 bytes zero
        buf.short.toInt() and 0xFFFF shouldBe 0
    }

    @Test
    fun `parseResponse extracts address and port from crafted response`() {
        val pkt = craftResponse(type = 0x0002, ssrc = 99, address = "1.2.3.4", port = 51234)
        val result = IpDiscovery.parseResponse(pkt)
        result.address shouldBe "1.2.3.4"
        result.port shouldBe 51234
    }

    @Test
    fun `parseResponse throws on wrong type`() {
        val pkt = craftResponse(type = 0x0001, ssrc = 99, address = "1.2.3.4", port = 51234)
        shouldThrow<IllegalArgumentException> { IpDiscovery.parseResponse(pkt) }
    }

    @Test
    fun `parseResponse handles short null-padded address`() {
        val pkt = craftResponse(type = 0x0002, ssrc = 7, address = "10.0.0.1", port = 4096)
        val r = IpDiscovery.parseResponse(pkt)
        r.address shouldBe "10.0.0.1"
        r.port shouldBe 4096
    }

    @Test
    fun `parseResponse throws on wrong packet length`() {
        shouldThrow<IllegalArgumentException> { IpDiscovery.parseResponse(ByteArray(73)) }
    }

    private fun craftResponse(type: Int, ssrc: Int, address: String, port: Int): ByteArray {
        val pkt = ByteArray(74)
        val buf = ByteBuffer.wrap(pkt).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(type.toShort())
        buf.putShort(70.toShort())
        buf.putInt(ssrc)
        val addrBytes = address.toByteArray(Charsets.US_ASCII)
        System.arraycopy(addrBytes, 0, pkt, 8, addrBytes.size)
        // remaining bytes in address slot are already zero
        buf.position(72)
        buf.putShort(port.toShort())
        return pkt
    }
}
