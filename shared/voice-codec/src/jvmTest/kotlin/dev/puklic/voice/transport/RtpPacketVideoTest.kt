package dev.puklic.voice.transport

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RtpPacketVideoTest {

    @Test
    fun `writeHeader with H264 payload type and marker bit sets byte 1 correctly`() {
        val header = RtpPacket.writeHeader(
            sequence = 1,
            timestamp = 0,
            ssrc = 0,
            payloadType = RtpPacket.PAYLOAD_TYPE_H264,
            marker = true,
        )
        // byte 1: marker(1) | payloadType(7) = 0x80 | (0x65 & 0x7F) = 0xE5
        header[1] shouldBe 0xE5.toByte()
    }

    @Test
    fun `writeHeader with VP8 payload type and no marker bit`() {
        val header = RtpPacket.writeHeader(
            sequence = 0,
            timestamp = 0,
            ssrc = 0,
            payloadType = RtpPacket.PAYLOAD_TYPE_VP8,
            marker = false,
        )
        // byte 1: 0x00 | (0x67 & 0x7F) = 0x67
        header[1] shouldBe 0x67.toByte()
    }

    @Test
    fun `readHeader exposes marker and payload type for H264 packet`() {
        val bytes = RtpPacket.writeHeader(
            sequence = 42,
            timestamp = 9000,
            ssrc = 0xABCD,
            payloadType = RtpPacket.PAYLOAD_TYPE_H264,
            marker = true,
        )
        val parsed = RtpPacket.readHeader(bytes)
        parsed.sequence shouldBe 42.toShort()
        parsed.timestamp shouldBe 9000
        parsed.ssrc shouldBe 0xABCD
        parsed.payloadType shouldBe RtpPacket.PAYLOAD_TYPE_H264
        parsed.marker shouldBe true
    }

    @Test
    fun `readHeader exposes marker false for Opus packet without marker`() {
        val bytes = RtpPacket.writeHeader(sequence = 1, timestamp = 0, ssrc = 0)
        val parsed = RtpPacket.readHeader(bytes)
        parsed.payloadType shouldBe RtpPacket.PAYLOAD_TYPE_OPUS
        parsed.marker shouldBe false
    }
}
