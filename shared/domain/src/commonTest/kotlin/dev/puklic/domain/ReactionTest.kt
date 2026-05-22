package dev.puklic.domain

import dev.puklic.ids.EmojiId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/chat-model.md — "Reaction", "EmojiRef"
 *
 * Covers:
 *  - Reaction construction with Unicode and Custom emoji
 *  - EmojiRef.Unicode equality by codepoint
 *  - EmojiRef.Custom equality by id, name, animated
 *  - EmojiRef sealed hierarchy exhaustiveness via when()
 *  - Reaction.me flag (logged-in user reacted)
 *  - Reaction.countDetails optional (null and non-null)
 *  - ReactionCountDetails construction
 *  - Structural equality and inequality
 */
class ReactionTest {

    // ── EmojiRef.Unicode ──────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "data class Unicode(val codepoint: String) : EmojiRef"
     */
    @Test
    fun `EmojiRef Unicode construction preserves codepoint`() {
        val emoji = EmojiRef.Unicode("👍")
        assertEquals("👍", emoji.codepoint)
    }

    @Test
    fun `EmojiRef Unicode with multi-codepoint emoji is preserved`() {
        // Family emoji: multiple code points joined by ZWJ
        val familyEmoji = "👨‍👩‍👧"
        val emoji = EmojiRef.Unicode(familyEmoji)
        assertEquals(familyEmoji, emoji.codepoint)
    }

    @Test
    fun `two EmojiRef Unicode instances with same codepoint are equal`() {
        val a = EmojiRef.Unicode("❤️")
        val b = EmojiRef.Unicode("❤️")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two EmojiRef Unicode instances with different codepoints are not equal`() {
        val a = EmojiRef.Unicode("👍")
        val b = EmojiRef.Unicode("👎")
        assertNotEquals(a, b)
    }

    // ── EmojiRef.Custom ───────────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "data class Custom(val id: EmojiId, val name: String, val animated: Boolean)"
     */
    @Test
    fun `EmojiRef Custom construction preserves all fields`() {
        val emoji = EmojiRef.Custom(
            id = EmojiId(123456789L),
            name = "puklic_logo",
            animated = false,
        )
        assertEquals(EmojiId(123456789L), emoji.id)
        assertEquals("puklic_logo", emoji.name)
        assertFalse(emoji.animated)
    }

    @Test
    fun `EmojiRef Custom animated flag true is preserved`() {
        val emoji = EmojiRef.Custom(
            id = EmojiId(987654321L),
            name = "dancing_cat",
            animated = true,
        )
        assertTrue(emoji.animated)
    }

    @Test
    fun `two EmojiRef Custom instances with same fields are equal`() {
        val a = EmojiRef.Custom(EmojiId(1L), "wave", false)
        val b = EmojiRef.Custom(EmojiId(1L), "wave", false)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `EmojiRef Custom instances differing only by animated are not equal`() {
        val static = EmojiRef.Custom(EmojiId(1L), "fire", false)
        val animated = EmojiRef.Custom(EmojiId(1L), "fire", true)
        assertNotEquals(static, animated)
    }

    @Test
    fun `EmojiRef Custom instances differing only by name are not equal`() {
        val a = EmojiRef.Custom(EmojiId(1L), "fire", false)
        val b = EmojiRef.Custom(EmojiId(1L), "flame", false)
        assertNotEquals(a, b)
    }

    @Test
    fun `EmojiRef Unicode and EmojiRef Custom are never equal`() {
        val unicode: EmojiRef = EmojiRef.Unicode("🔥")
        val custom: EmojiRef = EmojiRef.Custom(EmojiId(1L), "fire", false)
        assertNotEquals<Any>(unicode, custom)
    }

    // ── EmojiRef sealed hierarchy exhaustiveness ──────────────────────────────

    /**
     * Spec: chat-model.md — "sealed interface EmojiRef"
     * The when() with no else branch proves the sealed hierarchy is complete.
     */
    @Test
    fun `sealed EmojiRef when expression covers Unicode and Custom without else`() {
        val emojis: List<EmojiRef> = listOf(
            EmojiRef.Unicode("😊"),
            EmojiRef.Custom(EmojiId(1L), "puklic", false),
        )
        val labels = emojis.map { emoji ->
            when (emoji) {
                is EmojiRef.Unicode -> "unicode"
                is EmojiRef.Custom  -> "custom"
            }
        }
        assertEquals(listOf("unicode", "custom"), labels)
    }

    // ── Reaction construction ─────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — Reaction fields
     */
    @Test
    fun `Reaction with Unicode emoji preserves all fields`() {
        val reaction = Reaction(
            emoji = EmojiRef.Unicode("👍"),
            count = 42,
            me = true,
            countDetails = null,
        )
        assertEquals(EmojiRef.Unicode("👍"), reaction.emoji)
        assertEquals(42, reaction.count)
        assertTrue(reaction.me)
        assertNull(reaction.countDetails)
    }

    @Test
    fun `Reaction with Custom emoji preserves all fields`() {
        val emoji = EmojiRef.Custom(EmojiId(999L), "puklic_wave", true)
        val reaction = Reaction(
            emoji = emoji,
            count = 7,
            me = false,
            countDetails = null,
        )
        assertEquals(emoji, reaction.emoji)
        assertEquals(7, reaction.count)
        assertFalse(reaction.me)
    }

    @Test
    fun `Reaction with count zero is valid`() {
        // Edge case: Discord may send count=0 for a reaction being removed
        val reaction = Reaction(
            emoji = EmojiRef.Unicode("❌"),
            count = 0,
            me = false,
            countDetails = null,
        )
        assertEquals(0, reaction.count)
    }

    // ── ReactionCountDetails ──────────────────────────────────────────────────

    /**
     * Spec: chat-model.md — "countDetails: ReactionCountDetails? // burst vs normal"
     */
    @Test
    fun `Reaction with ReactionCountDetails preserves burst and normal counts`() {
        val details = ReactionCountDetails(
            burst = 3,
            normal = 10,
        )
        val reaction = Reaction(
            emoji = EmojiRef.Unicode("🚀"),
            count = 13,
            me = false,
            countDetails = details,
        )
        assertEquals(3, reaction.countDetails?.burst)
        assertEquals(10, reaction.countDetails?.normal)
    }

    @Test
    fun `ReactionCountDetails structural equality with same values`() {
        val a = ReactionCountDetails(burst = 5, normal = 20)
        val b = ReactionCountDetails(burst = 5, normal = 20)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `ReactionCountDetails instances differing in burst are not equal`() {
        val a = ReactionCountDetails(burst = 1, normal = 10)
        val b = ReactionCountDetails(burst = 2, normal = 10)
        assertNotEquals(a, b)
    }

    // ── Reaction structural equality ──────────────────────────────────────────

    @Test
    fun `two Reaction instances with identical fields are equal`() {
        val a = Reaction(
            emoji = EmojiRef.Unicode("✅"),
            count = 5,
            me = true,
            countDetails = ReactionCountDetails(burst = 2, normal = 3),
        )
        val b = Reaction(
            emoji = EmojiRef.Unicode("✅"),
            count = 5,
            me = true,
            countDetails = ReactionCountDetails(burst = 2, normal = 3),
        )
        assertEquals(a, b)
    }

    @Test
    fun `Reaction instances differing only by count are not equal`() {
        val a = Reaction(emoji = EmojiRef.Unicode("💙"), count = 1, me = false, countDetails = null)
        val b = a.copy(count = 2)
        assertNotEquals(a, b)
    }

    @Test
    fun `Reaction instances differing only by me flag are not equal`() {
        val a = Reaction(emoji = EmojiRef.Unicode("💙"), count = 5, me = false, countDetails = null)
        val b = a.copy(me = true)
        assertNotEquals(a, b)
    }
}
