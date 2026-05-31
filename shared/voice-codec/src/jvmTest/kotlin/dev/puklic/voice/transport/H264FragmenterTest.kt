package dev.puklic.voice.transport

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class H264FragmenterTest {

    @Test
    fun `small NAL fits in a single fragment with end=true`() {
        // First byte 0x65 = F=0, NRI=3, Type=5 (IDR slice)
        val nalu = ByteArray(500) { 0x55 }.also { it[0] = 0x65.toByte() }
        val fragments = H264Fragmenter.fragment(nalu)
        fragments shouldHaveSize 1
        fragments[0].end shouldBe true
        fragments[0].payload.contentEquals(nalu) shouldBe true
    }

    @Test
    fun `large NAL is split into FU-A fragments with correct S and E bits`() {
        // 5000-byte NAL forces multi-fragment.
        // First byte: NRI=2 (0x40), Type=7 (SPS) -> 0x47
        val nalu = ByteArray(5000) { (it and 0xFF).toByte() }.also { it[0] = 0x47.toByte() }
        val fragments = H264Fragmenter.fragment(nalu)
        (fragments.size >= 2) shouldBe true

        // First fragment: S=1, E=0
        val firstFu = fragments.first().payload
        (firstFu[1].toInt() and 0x80) shouldBe 0x80
        (firstFu[1].toInt() and 0x40) shouldBe 0x00
        fragments.first().end shouldBe false

        // Last fragment: S=0, E=1
        val lastFu = fragments.last().payload
        (lastFu[1].toInt() and 0x80) shouldBe 0x00
        (lastFu[1].toInt() and 0x40) shouldBe 0x40
        fragments.last().end shouldBe true

        // Middle fragments: S=0, E=0
        if (fragments.size > 2) {
            fragments.drop(1).dropLast(1).forEach { mid ->
                (mid.payload[1].toInt() and 0x80) shouldBe 0x00
                (mid.payload[1].toInt() and 0x40) shouldBe 0x00
                mid.end shouldBe false
            }
        }
    }

    @Test
    fun `FU-A indicator byte preserves NRI and uses type 28`() {
        val nalu = ByteArray(3000) { 0 }.also { it[0] = 0x47.toByte() } // NRI=2 (0x40), Type=7
        val fragments = H264Fragmenter.fragment(nalu)
        val indicator = fragments.first().payload[0].toInt() and 0xFF
        // F bit must be 0
        (indicator and 0x80) shouldBe 0x00
        // NRI = 0x40 (preserved)
        (indicator and 0x60) shouldBe 0x40
        // Type = 28 (FU-A)
        (indicator and 0x1F) shouldBe 28
    }

    @Test
    fun `FU-A header byte preserves original NAL type bits`() {
        // Type=5 (IDR), NRI=3 -> first byte 0x65
        val nalu = ByteArray(3000) { 0 }.also { it[0] = 0x65.toByte() }
        val fragments = H264Fragmenter.fragment(nalu)
        fragments.forEach { frag ->
            val fuHeader = frag.payload[1].toInt() and 0x1F
            fuHeader shouldBe 5
        }
    }

    @Test
    fun `all fragments fit under safe MTU payload limit`() {
        val nalu = ByteArray(20_000) { (it and 0xFF).toByte() }.also { it[0] = 0x65.toByte() }
        val fragments = H264Fragmenter.fragment(nalu)
        fragments.forEach { frag ->
            (frag.payload.size <= H264Fragmenter.MAX_PAYLOAD) shouldBe true
        }
    }
}
