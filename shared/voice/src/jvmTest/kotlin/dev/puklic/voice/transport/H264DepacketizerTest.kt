package dev.puklic.voice.transport

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class H264DepacketizerTest {

    @Test
    fun `single NAL emitted on marker with Annex-B start code`() {
        val depacketizer = H264Depacketizer()
        // NRI=3, Type=5 (IDR)
        val nal = byteArrayOf(0x65.toByte(), 0x01, 0x02, 0x03)
        val out = depacketizer.push(nal, marker = true)
        out shouldNotBe null
        out!![0] shouldBe 0
        out[1] shouldBe 0
        out[2] shouldBe 0
        out[3] shouldBe 1
        out.copyOfRange(4, out.size).contentEquals(nal) shouldBe true
    }

    @Test
    fun `single NAL without marker buffers until next marker`() {
        val depacketizer = H264Depacketizer()
        val nal1 = byteArrayOf(0x67.toByte(), 0x42, 0x00, 0x1E) // SPS (type 7)
        val nal2 = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x3C, 0x80.toByte()) // PPS (type 8)
        depacketizer.push(nal1, marker = false) shouldBe null
        val out = depacketizer.push(nal2, marker = true)!!
        // 4 + 4 + 4 + 4 = 16
        out.size shouldBe 16
        // Two start codes
        out.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1)) shouldBe true
        out.copyOfRange(8, 12).contentEquals(byteArrayOf(0, 0, 0, 1)) shouldBe true
        out.copyOfRange(4, 8).contentEquals(nal1) shouldBe true
        out.copyOfRange(12, 16).contentEquals(nal2) shouldBe true
    }

    @Test
    fun `FU-A three fragments S M E reassemble to original NAL`() {
        // Original NAL: NRI=3 (0x60), Type=5 (IDR) → first byte 0x65, then payload bytes.
        val originalBody = ByteArray(300) { ((it + 1) and 0xFF).toByte() }
        val originalNal = ByteArray(1 + originalBody.size).also {
            it[0] = 0x65.toByte()
            originalBody.copyInto(it, destinationOffset = 1)
        }
        // FU-A indicator: F=0, NRI=3 (0x60), Type=28 → 0x7C
        val indicator: Byte = 0x7C.toByte()
        // FU header: type=5 (original) for all; S=1 first, E=1 last
        val fuStart = (0x80 or 5).toByte()
        val fuMid = (0x00 or 5).toByte()
        val fuEnd = (0x40 or 5).toByte()

        val chunk1 = originalBody.copyOfRange(0, 100)
        val chunk2 = originalBody.copyOfRange(100, 200)
        val chunk3 = originalBody.copyOfRange(200, 300)

        val pkt1 = byteArrayOf(indicator, fuStart) + chunk1
        val pkt2 = byteArrayOf(indicator, fuMid) + chunk2
        val pkt3 = byteArrayOf(indicator, fuEnd) + chunk3

        val d = H264Depacketizer()
        d.push(pkt1, marker = false) shouldBe null
        d.push(pkt2, marker = false) shouldBe null
        val out = d.push(pkt3, marker = true)!!
        // out = 4-byte start code + original NAL
        out.size shouldBe 4 + originalNal.size
        out.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1)) shouldBe true
        out.copyOfRange(4, out.size).contentEquals(originalNal) shouldBe true
    }

    @Test
    fun `STAP-A with two NALs splits both out`() {
        // STAP-A indicator: F=0, NRI=3, Type=24 → 0x78
        // Aggregated NAL 1 (size=4): bytes [0x67, 0x42, 0x00, 0x1E]
        // Aggregated NAL 2 (size=4): bytes [0x68, 0xCE, 0x3C, 0x80]
        val stap = byteArrayOf(
            0x78.toByte(),
            0x00, 0x04, 0x67.toByte(), 0x42, 0x00, 0x1E,
            0x00, 0x04, 0x68.toByte(), 0xCE.toByte(), 0x3C, 0x80.toByte(),
        )
        val out = H264Depacketizer().push(stap, marker = true)!!
        // 4 (start) + 4 (nal1) + 4 (start) + 4 (nal2) = 16
        out.size shouldBe 16
        out.copyOfRange(0, 4).contentEquals(byteArrayOf(0, 0, 0, 1)) shouldBe true
        out.copyOfRange(4, 8).contentEquals(byteArrayOf(0x67.toByte(), 0x42, 0x00, 0x1E)) shouldBe true
        out.copyOfRange(8, 12).contentEquals(byteArrayOf(0, 0, 0, 1)) shouldBe true
        out.copyOfRange(12, 16).contentEquals(byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x3C, 0x80.toByte())) shouldBe true
    }

    @Test
    fun `FU-A mid fragment without start is dropped`() {
        val indicator: Byte = 0x7C.toByte()
        val fuMid = (0x00 or 5).toByte()
        val pkt = byteArrayOf(indicator, fuMid, 0x10, 0x20, 0x30)
        H264Depacketizer().push(pkt, marker = true) shouldBe null
    }

    @Test
    fun `roundtrip fragment then depacketize reproduces original NAL`() {
        val original = ByteArray(5000) { (it and 0xFF).toByte() }.also { it[0] = 0x65.toByte() }
        val fragments = H264Fragmenter.fragment(original)
        val d = H264Depacketizer()
        var assembled: ByteArray? = null
        for ((index, frag) in fragments.withIndex()) {
            val isLast = index == fragments.lastIndex
            val out = d.push(frag.payload, marker = isLast)
            if (out != null) assembled = out
        }
        assembled shouldNotBe null
        // Strip 4-byte start code
        val nalOnly = assembled!!.copyOfRange(4, assembled.size)
        nalOnly.contentEquals(original) shouldBe true
    }
}
