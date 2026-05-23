package dev.puklic.repositories

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.Reaction

/**
 * Pure functional mutator for [Reaction] lists.
 *
 * Spec: docs/03_infrastructure/architect-reports/2026-05-23-reactions.md — Slice 1.
 *
 * All operations are pure and produce new lists. Matching is by structural equality
 * of [EmojiRef] (sealed data classes). Self-add is idempotent so the optimistic local
 * echo cannot double-count when the gateway event arrives.
 */
internal object ReactionMutator {

    fun add(list: List<Reaction>, emoji: EmojiRef, isMe: Boolean): List<Reaction> {
        val index = list.indexOfFirst { it.emoji == emoji }
        if (index < 0) {
            return list + Reaction(emoji = emoji, count = 1, me = isMe, countDetails = null)
        }
        val existing = list[index]
        if (isMe && existing.me) {
            // Idempotent: optimistic echo already applied.
            return list
        }
        val updated = existing.copy(
            count = existing.count + 1,
            me = existing.me || isMe,
        )
        return list.toMutableList().also { it[index] = updated }
    }

    fun remove(list: List<Reaction>, emoji: EmojiRef, isMe: Boolean): List<Reaction> {
        val index = list.indexOfFirst { it.emoji == emoji }
        if (index < 0) return list
        val existing = list[index]
        val newCount = existing.count - 1
        val newMe = if (isMe) false else existing.me
        if (newCount <= 0) {
            return list.toMutableList().also { it.removeAt(index) }
        }
        val updated = existing.copy(count = newCount, me = newMe)
        return list.toMutableList().also { it[index] = updated }
    }

    fun clearAll(): List<Reaction> = emptyList()

    fun clearEmoji(list: List<Reaction>, emoji: EmojiRef): List<Reaction> =
        list.filterNot { it.emoji == emoji }
}
