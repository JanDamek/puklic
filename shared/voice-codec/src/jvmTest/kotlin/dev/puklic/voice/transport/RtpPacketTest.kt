package dev.puklic.voice.transport

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RtpPacketTest {

    @Test
    fun `writeHeader produces 12-byte header with expected flags and big-endian fields`() {
        val header = RtpPacket.writeHeader(sequence = 1, timestamp = 960, ssrc = 12345)
        header.size shouldBe 12
        header[0] shouldBe 0x80.toByte()
        header[1] shouldBe 0x78.toByte()
        // sequence = 1
        header[2] shouldBe 0x00.toByte()
        header[3] shouldBe 0x01.toByte()
        // timestamp = 960 = 0x000003C0
        header[4] shouldBe 0x00.toByte()
        header[5] shouldBe 0x00.toByte()
        header[6] shouldBe 0x03.toByte()
        header[7] shouldBe 0xC0.toByte()
        // ssrc = 12345 = 0x00003039
        header[8] shouldBe 0x00.toByte()
        header[9] shouldBe 0x00.toByte()
        header[10] shouldBe 0x30.toByte()
        header[11] shouldBe 0x39.toByte()
    }

    @Test
    fun `readHeader round-trips writeHeader`() {
        val header = RtpPacket.writeHeader(sequence = 0xBEEF.toShort(), timestamp = 0x01020304, ssrc = 0x0A0B0C0D)
        val parsed = RtpPacket.readHeader(header)
        parsed.sequence shouldBe 0xBEEF.toShort()
        parsed.timestamp shouldBe 0x01020304
        parsed.ssrc shouldBe 0x0A0B0C0D
    }

    @Test
    fun `readHeader throws on bad version byte`() {
        val bad = RtpPacket.writeHeader(0, 0, 0).copyOf().also { it[0] = 0x40.toByte() }
        shouldThrow<IllegalArgumentException> { RtpPacket.readHeader(bad) }
    }

    @Test
    fun `readHeader throws on bad payload type`() {
        val bad = RtpPacket.writeHeader(0, 0, 0).copyOf().also { it[1] = 0x00.toByte() }
        shouldThrow<IllegalArgumentException> { RtpPacket.readHeader(bad) }
    }

    @Test
    fun `readHeader throws on short input`() {
        shouldThrow<IllegalArgumentException> { RtpPacket.readHeader(ByteArray(11)) }
    }
}
