package dev.puklic.voice.screenshare.encoder

import dev.puklic.voice.transport.RtpPacket
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class VideoCodecPayloadTypeTest {

    @Test
    fun `H264 maps to RTP payload type 0x65`() {
        VideoCodec.H264.payloadType() shouldBe RtpPacket.PAYLOAD_TYPE_H264
    }

    @Test
    fun `VP8 maps to RTP payload type 0x67`() {
        VideoCodec.VP8.payloadType() shouldBe RtpPacket.PAYLOAD_TYPE_VP8
    }
}
