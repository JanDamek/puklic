package dev.puklic.repositories

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Reaction
import dev.puklic.ids.EmojiId
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Spec: docs/03_infrastructure/architect-reports/2026-05-23-reactions.md — Slice 1
 *
 * Pure mutator for Reaction lists. Idempotent self-add to tolerate optimistic echo.
 */
class ReactionMutatorTest {

    private val thumbsUp: EmojiRef = EmojiRef.Unicode("👍")
    private val heart: EmojiRef = EmojiRef.Unicode("❤️")
    private val customFire: EmojiRef = EmojiRef.Custom(EmojiId(42L), "fire", false)
    private val customFireAnimated: EmojiRef = EmojiRef.Custom(EmojiId(42L), "fire", true)

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    fun add_new_unicode_by_other_user_appends_entry_with_me_false() {
        val result = ReactionMutator.add(emptyList(), thumbsUp, isMe = false)
        result shouldBe listOf(Reaction(thumbsUp, 1, false, null))
    }

    @Test
    fun add_new_unicode_by_self_appends_entry_with_me_true() {
        val result = ReactionMutator.add(emptyList(), thumbsUp, isMe = true)
        result shouldBe listOf(Reaction(thumbsUp, 1, true, null))
    }

    @Test
    fun add_existing_by_other_user_bumps_count_and_preserves_me() {
        val initial = listOf(Reaction(thumbsUp, 1, true, null))
        val result = ReactionMutator.add(initial, thumbsUp, isMe = false)
        result shouldBe listOf(Reaction(thumbsUp, 2, true, null))
    }

    @Test
    fun add_existing_by_self_when_me_false_bumps_count_and_sets_me_true() {
        val initial = listOf(Reaction(thumbsUp, 3, false, null))
        val result = ReactionMutator.add(initial, thumbsUp, isMe = true)
        result shouldBe listOf(Reaction(thumbsUp, 4, true, null))
    }

    @Test
    fun add_existing_by_self_when_me_true_is_idempotent_noop() {
        val initial = listOf(Reaction(thumbsUp, 5, true, null))
        val result = ReactionMutator.add(initial, thumbsUp, isMe = true)
        result shouldBe initial
    }

    @Test
    fun add_distinct_emoji_appends_separate_entry() {
        val initial = listOf(Reaction(thumbsUp, 2, false, null))
        val result = ReactionMutator.add(initial, heart, isMe = true)
        result shouldBe listOf(
            Reaction(thumbsUp, 2, false, null),
            Reaction(heart, 1, true, null),
        )
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    fun remove_existing_by_other_user_decrements_count_preserves_me() {
        val initial = listOf(Reaction(thumbsUp, 3, true, null))
        val result = ReactionMutator.remove(initial, thumbsUp, isMe = false)
        result shouldBe listOf(Reaction(thumbsUp, 2, true, null))
    }

    @Test
    fun remove_existing_by_self_clears_me_flag_and_decrements() {
        val initial = listOf(Reaction(thumbsUp, 3, true, null))
        val result = ReactionMutator.remove(initial, thumbsUp, isMe = true)
        result shouldBe listOf(Reaction(thumbsUp, 2, false, null))
    }

    @Test
    fun remove_last_reactor_drops_entry() {
        val initial = listOf(Reaction(thumbsUp, 1, true, null))
        val result = ReactionMutator.remove(initial, thumbsUp, isMe = true)
        result shouldBe emptyList()
    }

    @Test
    fun remove_nonexistent_emoji_is_noop() {
        val initial = listOf(Reaction(thumbsUp, 1, false, null))
        val result = ReactionMutator.remove(initial, heart, isMe = true)
        result shouldBe initial
    }

    @Test
    fun remove_from_empty_list_is_noop() {
        val result = ReactionMutator.remove(emptyList(), thumbsUp, isMe = false)
        result shouldBe emptyList()
    }

    // ── clearAll / clearEmoji ─────────────────────────────────────────────────

    @Test
    fun clearAll_returns_empty_list() {
        ReactionMutator.clearAll() shouldBe emptyList()
    }

    @Test
    fun clearEmoji_removes_only_matching_entry() {
        val initial = listOf(
            Reaction(thumbsUp, 2, false, null),
            Reaction(heart, 1, true, null),
            Reaction(customFire, 3, false, null),
        )
        val result = ReactionMutator.clearEmoji(initial, heart)
        result shouldBe listOf(
            Reaction(thumbsUp, 2, false, null),
            Reaction(customFire, 3, false, null),
        )
    }

    @Test
    fun clearEmoji_nonexistent_is_noop() {
        val initial = listOf(Reaction(thumbsUp, 2, false, null))
        ReactionMutator.clearEmoji(initial, heart) shouldBe initial
    }

    // ── Custom emoji equality ─────────────────────────────────────────────────

    @Test
    fun add_custom_emoji_matches_by_structural_equality() {
        val initial = listOf(Reaction(customFire, 1, false, null))
        val result = ReactionMutator.add(initial, EmojiRef.Custom(EmojiId(42L), "fire", false), isMe = true)
        result shouldBe listOf(Reaction(customFire, 2, true, null))
    }

    @Test
    fun add_custom_emoji_differing_by_animated_creates_separate_entry() {
        val initial = listOf(Reaction(customFire, 1, false, null))
        val result = ReactionMutator.add(initial, customFireAnimated, isMe = false)
        result shouldBe listOf(
            Reaction(customFire, 1, false, null),
            Reaction(customFireAnimated, 1, false, null),
        )
    }
}
