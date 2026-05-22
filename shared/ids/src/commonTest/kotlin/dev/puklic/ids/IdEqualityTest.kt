package dev.puklic.ids

/**
 * Spec: docs/02_domain/chat-model.md — "Identifiers"
 * Spec: docs/03_infrastructure/architect-reports/2026-05-21-gradle-setup.md §5 shared/ids/
 *
 * Verifies construction, structural equality, hashCode contract, and toString
 * for all seven type-safe ID value classes:
 *   UserId, GuildId, ChannelId, MessageId, RoleId, EmojiId, AttachmentId
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * COMPILE-TIME TYPE-SAFETY NOTE (cannot be verified at runtime):
 *
 *   Writing `UserId(123L) == ChannelId(123L)` in Kotlin source is a
 *   TYPE MISMATCH compile error — the two types have no common equality
 *   supertype that carries the `.value` property.  The Kotlin compiler
 *   enforces this without any runtime check.
 *
 *   The test `each ID type has a distinct KClass` below is the closest
 *   runtime proxy: it confirms every class is a genuinely separate type,
 *   not a type alias or common interface.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class IdEqualityTest {

    // ── Construction: each type wraps Long and exposes .value ─────────────────

    /**
     * Spec: docs/02_domain/chat-model.md — `@JvmInline value class UserId(val value: Long)`
     * The .value property must reflect exactly the Long passed to the constructor.
     */
    @kotlin.test.Test
    fun `UserId construction with positive value preserves value`() {
        kotlin.test.assertEquals(123L, UserId(123L).value)
    }

    @kotlin.test.Test
    fun `UserId construction with zero preserves zero`() {
        // Edge case: zero is a valid Long; must round-trip through the value class
        kotlin.test.assertEquals(0L, UserId(0L).value)
    }

    @kotlin.test.Test
    fun `UserId construction with Long MAX_VALUE preserves value`() {
        // Spec: docs/02_domain/chat-model.md — "Kotlin Long is sufficient (the positive range
        // covers everything until 2084)", so MAX_VALUE must not overflow or truncate
        kotlin.test.assertEquals(Long.MAX_VALUE, UserId(Long.MAX_VALUE).value)
    }

    @kotlin.test.Test
    fun `GuildId construction with positive value preserves value`() {
        kotlin.test.assertEquals(456L, GuildId(456L).value)
    }

    @kotlin.test.Test
    fun `ChannelId construction with positive value preserves value`() {
        kotlin.test.assertEquals(789L, ChannelId(789L).value)
    }

    @kotlin.test.Test
    fun `MessageId construction with positive value preserves value`() {
        kotlin.test.assertEquals(111L, MessageId(111L).value)
    }

    @kotlin.test.Test
    fun `RoleId construction with positive value preserves value`() {
        kotlin.test.assertEquals(222L, RoleId(222L).value)
    }

    @kotlin.test.Test
    fun `EmojiId construction with positive value preserves value`() {
        kotlin.test.assertEquals(333L, EmojiId(333L).value)
    }

    @kotlin.test.Test
    fun `AttachmentId construction with positive value preserves value`() {
        kotlin.test.assertEquals(444L, AttachmentId(444L).value)
    }

    // ── Equality: structural (value-based), per Kotlin @JvmInline contract ────

    /**
     * Spec: docs/02_domain/chat-model.md — "All data class instances have structural equality
     * (Kotlin default)"; the same principle applies to inline value classes — equality is
     * determined by the wrapped Long, not by reference identity.
     */
    @kotlin.test.Test
    fun `UserId structural equality with same value`() {
        kotlin.test.assertEquals(UserId(42L), UserId(42L))
    }

    @kotlin.test.Test
    fun `UserId structural inequality with different values`() {
        kotlin.test.assertNotEquals(UserId(1L), UserId(2L))
    }

    @kotlin.test.Test
    fun `GuildId structural equality with same value`() {
        kotlin.test.assertEquals(GuildId(100L), GuildId(100L))
    }

    @kotlin.test.Test
    fun `GuildId structural inequality with different values`() {
        kotlin.test.assertNotEquals(GuildId(100L), GuildId(200L))
    }

    @kotlin.test.Test
    fun `ChannelId structural equality with same value`() {
        kotlin.test.assertEquals(ChannelId(500L), ChannelId(500L))
    }

    @kotlin.test.Test
    fun `ChannelId structural inequality with different values`() {
        kotlin.test.assertNotEquals(ChannelId(1L), ChannelId(2L))
    }

    @kotlin.test.Test
    fun `MessageId structural equality with same value`() {
        kotlin.test.assertEquals(MessageId(1000L), MessageId(1000L))
    }

    @kotlin.test.Test
    fun `RoleId structural equality with same value`() {
        kotlin.test.assertEquals(RoleId(1L), RoleId(1L))
    }

    @kotlin.test.Test
    fun `EmojiId structural equality with same value`() {
        kotlin.test.assertEquals(EmojiId(77L), EmojiId(77L))
    }

    @kotlin.test.Test
    fun `AttachmentId structural equality with same value`() {
        kotlin.test.assertEquals(AttachmentId(99L), AttachmentId(99L))
    }

    // ── hashCode contract ─────────────────────────────────────────────────────

    /**
     * Kotlin value class hashCode is delegated to the wrapped type's hashCode.
     * Contract: equal objects must have equal hashCodes.
     */
    @kotlin.test.Test
    fun `UserId hashCode is consistent across two equal instances`() {
        // Two separate UserId instances with the same value must produce identical hashCodes.
        kotlin.test.assertEquals(UserId(55L).hashCode(), UserId(55L).hashCode())
    }

    @kotlin.test.Test
    fun `UserId hashCode differs for different values`() {
        // Long.hashCode() for consecutive integers produces distinct results — no
        // collision risk in this range (Long.hashCode = (value xor (value ushr 32)).toInt()).
        kotlin.test.assertNotEquals(UserId(1L).hashCode(), UserId(2L).hashCode())
    }

    @kotlin.test.Test
    fun `GuildId hashCode is consistent across two equal instances`() {
        kotlin.test.assertEquals(GuildId(100L).hashCode(), GuildId(100L).hashCode())
    }

    @kotlin.test.Test
    fun `MessageId hashCode is consistent across two equal instances`() {
        kotlin.test.assertEquals(MessageId(999L).hashCode(), MessageId(999L).hashCode())
    }

    // ── toString: must contain the wrapped Long value ─────────────────────────

    /**
     * Spec: docs/02_domain/chat-model.md — no explicit toString format required.
     * Kotlin's default for `@JvmInline value class UserId(val value: Long)` is
     * `"UserId(value=123)"`. The test accepts any format that includes the digits.
     */
    @kotlin.test.Test
    fun `UserId toString contains the wrapped Long digits`() {
        val result = UserId(123L).toString()
        kotlin.test.assertTrue(
            result.contains("123"),
            "Expected toString() to contain '123', actual: '$result'",
        )
    }

    @kotlin.test.Test
    fun `ChannelId toString contains the wrapped Long digits`() {
        val result = ChannelId(999L).toString()
        kotlin.test.assertTrue(
            result.contains("999"),
            "Expected toString() to contain '999', actual: '$result'",
        )
    }

    @kotlin.test.Test
    fun `GuildId toString with large value contains the digits`() {
        // Large value typical of a real Discord snowflake (17-digit Long)
        val guildId = GuildId(1234567890123456789L)
        val result = guildId.toString()
        kotlin.test.assertTrue(
            result.contains("1234567890123456789"),
            "Expected toString() to contain '1234567890123456789', actual: '$result'",
        )
    }

    // ── KClass distinctness: runtime proxy for compile-time type isolation ────

    /**
     * Spec: docs/02_domain/chat-model.md — "the compiler rejects mixing up ChannelId
     * with MessageId"
     *
     * Runtime check: all seven ID classes must be distinct KClass objects.
     * If any two shared the same KClass the type system could not distinguish them.
     *
     * Note: this does NOT replace the compile-time guarantee — it is supplementary evidence.
     * The real guarantee is that `UserId(1L) == ChannelId(1L)` is a compile error (TYPE MISMATCH).
     */
    @kotlin.test.Test
    fun `each ID type has a distinct KClass — runtime proxy for compile-time type isolation`() {
        val distinctClasses = setOf(
            UserId::class,
            GuildId::class,
            ChannelId::class,
            MessageId::class,
            RoleId::class,
            EmojiId::class,
            AttachmentId::class,
        )
        kotlin.test.assertEquals(
            7,
            distinctClasses.size,
            "All 7 ID types must be distinct KClass instances; found ${distinctClasses.size} distinct",
        )
    }
}
