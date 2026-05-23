package dev.puklic.voice.dave.gateway

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DaveBinaryFrameTest {

    @Test
    fun `parse extracts seq op and payload`() {
        // seq=0x1234, op=0x1A (=26 MLS_KEY_PACKAGE), payload=[0xAA,0xBB,0xCC]
        val frame = byteArrayOf(0x12, 0x34, 0x1A, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val parsed = DaveBinaryFrame.parse(frame)
        parsed shouldBe DaveBinaryFrame.Parsed(
            seq = 0x1234u,
            op = 26,
            payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
        )
    }

    @Test
    fun `parse accepts zero-byte payload`() {
        val frame = byteArrayOf(0x00, 0x01, 0x1E) // seq=1 op=30 payload=[]
        val parsed = DaveBinaryFrame.parse(frame)
        parsed shouldBe DaveBinaryFrame.Parsed(seq = 1u, op = 30, payload = ByteArray(0))
    }

    @Test
    fun `parse returns null for frame shorter than header`() {
        DaveBinaryFrame.parse(byteArrayOf()) shouldBe null
        DaveBinaryFrame.parse(byteArrayOf(0x00)) shouldBe null
        DaveBinaryFrame.parse(byteArrayOf(0x00, 0x01)) shouldBe null
    }

    @Test
    fun `write produces big-endian seq and op header`() {
        val frame = DaveBinaryFrame.write(seq = 0xABCDu, op = 25, payload = byteArrayOf(0x99.toByte()))
        frame.toList() shouldBe listOf(0xAB.toByte(), 0xCD.toByte(), 0x19.toByte(), 0x99.toByte())
    }

    @Test
    fun `parse write round-trip preserves bytes`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val frame = DaveBinaryFrame.write(seq = 4242u, op = 28, payload = payload)
        val parsed = DaveBinaryFrame.parse(frame)!!
        parsed.seq shouldBe 4242u
        parsed.op shouldBe 28
        parsed.payload.toList() shouldBe payload.toList()
    }
}
