package dev.puklic.voice.transport

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AnnexBSplitterTest {

    @Test
    fun `splits stream with 4-byte start codes`() {
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E,             // NAL 1: SPS
            0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(), // NAL 2: PPS
            0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(),  // NAL 3: IDR slice
        )
        val nalus = AnnexBSplitter.split(stream)
        nalus shouldHaveSize 3
        nalus[0].contentEquals(byteArrayOf(0x67, 0x42, 0x00, 0x1E)) shouldBe true
        nalus[1].contentEquals(byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())) shouldBe true
        nalus[2].contentEquals(byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())) shouldBe true
    }

    @Test
    fun `splits stream with 3-byte start codes`() {
        val stream = byteArrayOf(
            0, 0, 1, 0x67, 0x42, 0x1E,
            0, 0, 1, 0x65, 0x88.toByte(),
        )
        val nalus = AnnexBSplitter.split(stream)
        nalus shouldHaveSize 2
        nalus[0].contentEquals(byteArrayOf(0x67, 0x42, 0x1E)) shouldBe true
        nalus[1].contentEquals(byteArrayOf(0x65, 0x88.toByte())) shouldBe true
    }

    @Test
    fun `splits stream with mixed 3-byte and 4-byte start codes`() {
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x01,
            0, 0, 1, 0x68, 0x02,
            0, 0, 0, 1, 0x65, 0x03, 0x04,
        )
        val nalus = AnnexBSplitter.split(stream)
        nalus shouldHaveSize 3
        nalus[0].contentEquals(byteArrayOf(0x67, 0x01)) shouldBe true
        nalus[1].contentEquals(byteArrayOf(0x68, 0x02)) shouldBe true
        nalus[2].contentEquals(byteArrayOf(0x65, 0x03, 0x04)) shouldBe true
    }

    @Test
    fun `empty stream produces empty list`() {
        AnnexBSplitter.split(ByteArray(0)) shouldHaveSize 0
    }
}
