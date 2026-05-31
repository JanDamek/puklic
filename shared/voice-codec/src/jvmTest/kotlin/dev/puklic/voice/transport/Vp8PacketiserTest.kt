package dev.puklic.voice.transport

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * RFC 7741 §4.2 single-octet VP8 payload descriptor (no PictureID profile):
 *
 * ```
 *  0 1 2 3 4 5 6 7
 * +-+-+-+-+-+-+-+-+
 * |X|R|N|S|R| PID |
 * +-+-+-+-+-+-+-+-+
 * ```
 *
 * For the non-PictureID profile produced by libvpx with the default single-partition layout
 * the only bit that varies between RTP packets is `S` (Start of VP8 partition): 1 on the
 * first RTP packet of a frame, 0 on subsequent packets. `X=0`, `N=0`, `PID=0`.
 */
class Vp8PacketiserTest {

    @Test
    fun `small frame produces a single packet with S=1 descriptor`() {
        val frame = EncodedFrame(ByteArray(500) { (it and 0xFF).toByte() }, ts90k = 0, keyframe = true)
        val fragments = Vp8Packetiser.fragment(frame)
        fragments shouldHaveSize 1
        fragments[0].end shouldBe true
        // First byte of payload = single-octet descriptor with S=1 (0x10).
        (fragments[0].payload[0].toInt() and 0xFF) shouldBe 0x10
        // VP8 body follows the 1-byte descriptor.
        fragments[0].payload.size shouldBe 1 + 500
        fragments[0].payload.copyOfRange(1, fragments[0].payload.size)
            .contentEquals(frame.bytes) shouldBe true
    }

    @Test
    fun `large frame splits with S=1 on first packet and S=0 on subsequent`() {
        // 5000 B forces > 1 packet given safe MAX_PAYLOAD around 1168 B minus 1 B descriptor.
        val frame = EncodedFrame(ByteArray(5000) { (it and 0xFF).toByte() }, ts90k = 0, keyframe = true)
        val fragments = Vp8Packetiser.fragment(frame)
        (fragments.size >= 2) shouldBe true

        // First packet: descriptor 0x10 (S=1)
        (fragments.first().payload[0].toInt() and 0xFF) shouldBe 0x10
        fragments.first().end shouldBe false

        // Subsequent packets: descriptor 0x00 (S=0)
        fragments.drop(1).forEach { frag ->
            (frag.payload[0].toInt() and 0xFF) shouldBe 0x00
        }

        // Last fragment carries the end marker.
        fragments.last().end shouldBe true

        // Re-assembled payload (descriptors stripped) equals the original frame bytes.
        val joined = fragments.flatMap { it.payload.drop(1) }.toByteArray()
        joined.contentEquals(frame.bytes) shouldBe true
    }

    @Test
    fun `all fragments fit within safe MTU payload`() {
        val frame = EncodedFrame(ByteArray(20_000) { (it and 0xFF).toByte() }, ts90k = 0, keyframe = true)
        val fragments = Vp8Packetiser.fragment(frame)
        fragments.forEach { frag ->
            (frag.payload.size <= Vp8Packetiser.MAX_PAYLOAD) shouldBe true
        }
    }
}
