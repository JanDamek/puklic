package dev.puklic.repositories

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class AttachmentTooLargeErrorTest {

    @Test
    fun carries_filename_size_and_limit() {
        val err = AttachmentTooLargeError("big.zip", sizeBytes = 100L, limitBytes = 50L)
        err.filename shouldBe "big.zip"
        err.sizeBytes shouldBe 100L
        err.limitBytes shouldBe 50L
        err.message!! shouldContain "big.zip"
        err.message!! shouldContain "100"
        err.message!! shouldContain "50"
    }
}
