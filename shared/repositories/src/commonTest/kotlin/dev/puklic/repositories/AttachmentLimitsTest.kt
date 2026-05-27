package dev.puklic.repositories

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Issue #23 architect-comment Q4 — tier-driven attachment size limit. */
class AttachmentLimitsTest {

    @Test
    fun null_tier_returns_default_25_MiB() {
        AttachmentLimits.maxBytesFor(null) shouldBe 25L * 1024 * 1024
    }

    @Test
    fun tier_1_returns_25_MiB() {
        AttachmentLimits.maxBytesFor(1) shouldBe 25L * 1024 * 1024
    }

    @Test
    fun tier_2_returns_50_MiB() {
        AttachmentLimits.maxBytesFor(2) shouldBe 50L * 1024 * 1024
    }

    @Test
    fun tier_3_returns_100_MiB() {
        AttachmentLimits.maxBytesFor(3) shouldBe 100L * 1024 * 1024
    }

    @Test
    fun unknown_tier_value_falls_back_to_default() {
        AttachmentLimits.maxBytesFor(99) shouldBe 25L * 1024 * 1024
        AttachmentLimits.maxBytesFor(0) shouldBe 25L * 1024 * 1024
        AttachmentLimits.maxBytesFor(-1) shouldBe 25L * 1024 * 1024
    }
}
