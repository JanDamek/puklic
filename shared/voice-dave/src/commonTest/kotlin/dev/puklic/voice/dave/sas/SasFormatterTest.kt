package dev.puklic.voice.dave.sas

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SasFormatterTest {

    @Test
    fun `empty input renders empty string`() {
        SasFormatter.format(ByteArray(0)) shouldBe ""
    }

    @Test
    fun `two bytes render as one 5-digit group`() {
        SasFormatter.format(byteArrayOf(0x00, 0x01)) shouldBe "00001"
        SasFormatter.format(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) shouldBe "65535"
    }

    @Test
    fun `multi-word input is space-separated big-endian groups`() {
        val input = byteArrayOf(0x00, 0x01, 0x00, 0x02, 0x12, 0x34, 0xFF.toByte(), 0xFF.toByte())
        SasFormatter.format(input) shouldBe "00001 00002 04660 65535"
    }

    @Test
    fun `odd length input is left-padded so final group is full`() {
        SasFormatter.format(byteArrayOf(0x07)) shouldBe "00007"
    }

    @Test
    fun `format result has fixed group width`() {
        val out = SasFormatter.format(byteArrayOf(0x00, 0x09))
        out shouldBe "00009"
        out.length shouldBe SasFormatter.GROUP_DIGITS
    }
}
