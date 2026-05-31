package dev.puklic.voice.pipeline

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class JitterBufferTest {

    @Test
    fun `in-order packets drain in order`() {
        val jb = JitterBuffer()
        val out1 = jb.push(0, bytes(10))
        val out2 = jb.push(1, bytes(11))
        val out3 = jb.push(2, bytes(12))
        out1.size shouldBe 1
        out1[0]!![0] shouldBe 10
        out2[0]!![0] shouldBe 11
        out3[0]!![0] shouldBe 12
    }

    @Test
    fun `small reorder drains in sequence order`() {
        val jb = JitterBuffer()
        // 0 arrives, then 2 ahead of 1, then 1 fills gap.
        val a = jb.push(0, bytes(0))
        a.size shouldBe 1
        a[0]!![0] shouldBe 0
        val b = jb.push(2, bytes(2))
        // No drain yet — slot 1 missing and we don't know if it's lost yet.
        b shouldBe emptyList()
        val c = jb.push(1, bytes(1))
        // Now we drain 1 then 2.
        c.size shouldBe 2
        c[0]!![0] shouldBe 1
        c[1]!![0] shouldBe 2
    }

    @Test
    fun `missing slot emits null PLC marker once later packet arrives`() {
        val jb = JitterBuffer()
        jb.push(0, bytes(0))
        // Skip seq=1, push seq=2 and seq=3.
        jb.push(2, bytes(2)) shouldBe emptyList()
        val out = jb.push(3, bytes(3))
        // Should emit null for missing seq=1, then 2, then 3.
        out.size shouldBe 3
        out[0] shouldBe null
        out[1]!![0] shouldBe 2
        out[2]!![0] shouldBe 3
    }

    @Test
    fun `late packet older than window is dropped`() {
        val jb = JitterBuffer()
        jb.push(0, bytes(0))
        jb.push(1, bytes(1))
        jb.push(2, bytes(2))
        // Now expectedSeq = 3. A late seq=0 should be dropped.
        val out = jb.push(0, bytes(99))
        out shouldBe emptyList()
    }

    @Test
    fun `reset clears state and accepts new starting sequence`() {
        val jb = JitterBuffer()
        jb.push(100, bytes(1))
        jb.push(101, bytes(2))
        jb.reset()
        val out = jb.push(500, bytes(3))
        out.size shouldBe 1
        out[0]!![0] shouldBe 3
    }

    private fun bytes(marker: Byte): ByteArray = byteArrayOf(marker)
}
