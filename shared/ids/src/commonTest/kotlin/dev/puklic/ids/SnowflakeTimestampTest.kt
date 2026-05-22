package dev.puklic.ids

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

/**
 * Spec: docs/02_domain/discord-protocol.md — "Snowflake parsing"
 *   ```kotlin
 *   val DISCORD_EPOCH = 1420070400000L  // 2015-01-01
 *   fun Long.snowflakeTimestamp(): Instant =
 *       Instant.fromEpochMilliseconds((this shr 22) + DISCORD_EPOCH)
 *   ```
 *
 * These tests verify the extension function `Long.snowflakeTimestamp()` and the
 * top-level constant `DISCORD_EPOCH` that must exist in package `dev.puklic.ids`.
 *
 * Formula breakdown:
 *   - Discord snowflake bit layout (64 bits):
 *       [63..22] = 42 bits — milliseconds since DISCORD_EPOCH
 *       [21..17] = 5 bits  — internal worker ID
 *       [16..12] = 5 bits  — internal process ID
 *       [11..0]  = 12 bits — sequence number
 *   - `this shr 22` isolates the timestamp bits (arithmetic shift is safe for positive IDs)
 *   - `+ DISCORD_EPOCH` converts from Discord-epoch-relative to Unix-epoch-relative ms
 *
 * Edge case note — negative Long values:
 *   Discord IDs are logically unsigned 64-bit integers. Kotlin Long is signed.
 *   Overflow occurs for snowflakes >= 2^63. In practice this cannot happen until
 *   approximately the year 2084 (per docs/02_domain/chat-model.md). Testing negative
 *   Long snowflakes is therefore OUT OF SCOPE for Phase 1. Kotlin's `shr` is an
 *   arithmetic right shift (sign-extending), which would yield an incorrect timestamp
 *   for negative values — this is a known and accepted limitation per the spec.
 */
class SnowflakeTimestampTest {

    // ── DISCORD_EPOCH constant ─────────────────────────────────────────────────

    /**
     * Spec: docs/02_domain/discord-protocol.md — "val DISCORD_EPOCH = 1420070400000L // 2015-01-01"
     *
     * The constant must equal 1420070400000L, which is 2015-01-01T00:00:00Z expressed
     * in Unix epoch milliseconds. This is Discord's custom epoch (the "Discord epoch").
     */
    @kotlin.test.Test
    fun `DISCORD_EPOCH constant equals 1420070400000 which is 2015-01-01T00 00 00Z`() {
        kotlin.test.assertEquals(
            1_420_070_400_000L,
            DISCORD_EPOCH,
            "DISCORD_EPOCH must equal 1420070400000L (2015-01-01T00:00:00Z in Unix epoch ms)",
        )
    }

    // ── Boundary: snowflake 0L ─────────────────────────────────────────────────

    /**
     * Spec: docs/02_domain/discord-protocol.md — formula: (this shr 22) + DISCORD_EPOCH
     *
     * Snowflake 0L has all bits zero.
     *   - Timestamp bits: 0 shr 22 = 0 → 0 ms since Discord epoch
     *   - Result: DISCORD_EPOCH + 0 = 1420070400000L = 2015-01-01T00:00:00Z
     *
     * This is the minimum representable timestamp in the Discord snowflake space.
     */
    @kotlin.test.Test
    fun `snowflake 0L timestamp equals Discord epoch start 2015-01-01T00 00 00Z`() {
        val expected = Instant.fromEpochMilliseconds(1_420_070_400_000L) // 2015-01-01T00:00:00Z
        kotlin.test.assertEquals(expected, 0L.snowflakeTimestamp())
    }

    // ── Real Discord snowflake from official developer documentation ───────────

    /**
     * Spec: docs/02_domain/discord-protocol.md — snowflake example from Discord developer docs
     *
     * Snowflake: 175928847299117063
     * Expected:  2016-04-30T11:18:25.796Z
     *
     * Manual verification:
     *   175928847299117063 shr 22 = 41944705796
     *   41944705796 + 1420070400000 = 1462015105796 ms (Unix epoch)
     *   1462015105796 ms = 2016-04-30T11:18:25.796Z ✓
     *
     * The lower 22 bits of the original snowflake (131079) encode worker/process/sequence —
     * they do NOT contribute to the timestamp and are discarded by `shr 22`.
     */
    @kotlin.test.Test
    fun `real Discord snowflake 175928847299117063 produces 2016-04-30T11 18 25 796Z`() {
        val snowflake = 175_928_847_299_117_063L
        // 2016-04-30T11:18:25.796Z in Unix epoch milliseconds
        val expectedEpochMs = 1_462_015_105_796L
        val expected = Instant.fromEpochMilliseconds(expectedEpochMs)

        // Using Kotest shouldBe for richer failure message on mismatch
        snowflake.snowflakeTimestamp() shouldBe expected
    }

    // ── Formula correctness: shift amount must be exactly 22 bits ─────────────

    /**
     * Spec: docs/02_domain/discord-protocol.md — "(this shr 22) + DISCORD_EPOCH"
     *
     * A synthetic snowflake where the timestamp contribution is exactly 1 ms:
     *   snowflake = 1L shl 22 = 4194304
     *   4194304 shr 22 = 1
     *   expected = DISCORD_EPOCH + 1 = 2015-01-01T00:00:00.001Z
     *
     * If the implementation used `shr 10` or `shr 32` or any other shift, this test
     * would fail with a wrong timestamp, catching the bug immediately.
     */
    @kotlin.test.Test
    fun `snowflake with timestamp bit equal to 1ms produces Discord epoch plus 1ms`() {
        val snowflake = 1L shl 22 // = 4194304; timestamp bits encode exactly 1 ms offset
        val expected = Instant.fromEpochMilliseconds(1_420_070_400_001L) // DISCORD_EPOCH + 1 ms
        kotlin.test.assertEquals(expected, snowflake.snowflakeTimestamp())
    }

    // ── Lower 22 bits are discarded (only timestamp bits matter) ──────────────

    /**
     * Spec: docs/02_domain/discord-protocol.md — snowflake bit layout
     *
     * A snowflake with all lower 22 bits set but zero timestamp bits:
     *   snowflake = (1L shl 22) - 1 = 4194303 (binary: 0...0_1111...1 — 22 ones)
     *   4194303 shr 22 = 0
     *   expected = DISCORD_EPOCH + 0 = 2015-01-01T00:00:00Z
     *
     * The lower 22 bits (worker ID, process ID, sequence) must be completely ignored
     * when computing the timestamp.
     */
    @kotlin.test.Test
    fun `snowflake with only lower 22 bits set gives Discord epoch timestamp ignoring non-timestamp bits`() {
        val snowflake = (1L shl 22) - 1L // = 4194303 — all lower 22 bits set, timestamp = 0
        val expected = Instant.fromEpochMilliseconds(1_420_070_400_000L) // 2015-01-01T00:00:00Z
        kotlin.test.assertEquals(expected, snowflake.snowflakeTimestamp())
    }

    // ── Large real-world scale: snowflake corresponding to a recent timestamp ──

    /**
     * Spec: docs/02_domain/discord-protocol.md — snowflake formula must scale to 2026
     *
     * Validates the formula works for a snowflake in the expected operational range
     * of Phase 1 (year 2026). The snowflake uses only the timestamp portion (lower 22 bits = 0)
     * for deterministic verification.
     *
     * DISCORD_EPOCH = 1420070400000L (2015-01-01)
     * Offset for 2026-01-01T00:00:00Z = 1735689600000L (Unix ms)
     * Delta = 1735689600000 - 1420070400000 = 315619200000 ms since Discord epoch
     * Snowflake = 315619200000L shl 22 = 1324271944524800000L
     * Expected timestamp = 2026-01-01T00:00:00Z
     */
    @kotlin.test.Test
    fun `snowflake representing 2026-01-01T00 00 00Z produces correct Instant`() {
        // 2026-01-01T00:00:00Z in Unix epoch ms = 1735689600000L
        val targetUnixMs = 1_735_689_600_000L
        val discordEpoch = 1_420_070_400_000L
        val offsetMs = targetUnixMs - discordEpoch // = 315619200000L

        // Construct a snowflake with exactly that timestamp offset (lower 22 bits = 0)
        val snowflake = offsetMs shl 22 // = 1324271944524800000L

        val expected = Instant.fromEpochMilliseconds(targetUnixMs)
        kotlin.test.assertEquals(expected, snowflake.snowflakeTimestamp())
    }
}
