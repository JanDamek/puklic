package dev.puklic.voice.screenshare.encoder

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import kotlin.test.Test

class AnnexBStreamReaderTest {

    @Test
    fun `emits two NAL units separated by 4-byte start codes`() = runTest {
        // 00 00 00 01 09 10  |  00 00 00 01 09 30
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x09, 0x10,
            0, 0, 0, 1, 0x09, 0x30,
        )
        val nals = AnnexBStreamReader(ByteArrayInputStream(stream)).asFlow().toList()
        nals shouldHaveSize 2
        nals[0].contentEquals(byteArrayOf(0x09, 0x10)) shouldBe true
        nals[1].contentEquals(byteArrayOf(0x09, 0x30)) shouldBe true
    }

    @Test
    fun `emits NALs with mixed 3-byte and 4-byte start codes`() = runTest {
        // 00 00 00 01 67 01  |  00 00 01 68 02  |  00 00 00 01 65 03 04
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x01,
            0, 0, 1, 0x68, 0x02,
            0, 0, 0, 1, 0x65, 0x03, 0x04,
        )
        val nals = AnnexBStreamReader(ByteArrayInputStream(stream)).asFlow().toList()
        nals shouldHaveSize 3
        nals[0].contentEquals(byteArrayOf(0x67, 0x01)) shouldBe true
        nals[1].contentEquals(byteArrayOf(0x68, 0x02)) shouldBe true
        nals[2].contentEquals(byteArrayOf(0x65, 0x03, 0x04)) shouldBe true
    }

    @Test
    fun `trailing NAL with no following start code is emitted on EOF`() = runTest {
        // 00 00 00 01 09 10 -- only one start code, NAL emitted at EOF.
        val stream = byteArrayOf(0, 0, 0, 1, 0x09, 0x10, 0x20, 0x30)
        val nals = AnnexBStreamReader(ByteArrayInputStream(stream)).asFlow().toList()
        nals shouldHaveSize 1
        nals[0].contentEquals(byteArrayOf(0x09, 0x10, 0x20, 0x30)) shouldBe true
    }

    @Test
    fun `bytes before the first start code are discarded`() = runTest {
        // Random preamble | 00 00 00 01 65 99
        val stream = byteArrayOf(0x42, 0x43, 0, 0, 0, 1, 0x65, 0x99.toByte())
        val nals = AnnexBStreamReader(ByteArrayInputStream(stream)).asFlow().toList()
        nals shouldHaveSize 1
        nals[0].contentEquals(byteArrayOf(0x65, 0x99.toByte())) shouldBe true
    }

    @Test
    fun `empty stream produces no NALs`() = runTest {
        val nals = AnnexBStreamReader(ByteArrayInputStream(ByteArray(0))).asFlow().toList()
        nals shouldHaveSize 0
    }

    @Test
    fun `consecutive start codes produce an empty NAL between them`() = runTest {
        // Edge case: 00 00 00 01 | 00 00 00 01 65 01.
        // First start code is followed immediately by another → first NAL is empty, dropped.
        val stream = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0x65, 0x01)
        val nals = AnnexBStreamReader(ByteArrayInputStream(stream)).asFlow().toList()
        // First would-be NAL has 0 bytes after trimming the trailing 00 of the 4-byte
        // start code → not emitted. Only the final NAL is emitted (at EOF).
        nals shouldHaveSize 1
        nals[0].contentEquals(byteArrayOf(0x65, 0x01)) shouldBe true
    }
}
