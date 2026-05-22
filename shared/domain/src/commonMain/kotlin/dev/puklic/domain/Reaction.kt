package dev.puklic.domain

import dev.puklic.ids.EmojiId

sealed interface EmojiRef {
    data class Unicode(val codepoint: String) : EmojiRef
    data class Custom(val id: EmojiId, val name: String, val animated: Boolean) : EmojiRef
}

data class Reaction(
    val emoji: EmojiRef,
    val count: Int,
    val me: Boolean,
    val countDetails: ReactionCountDetails?,
)

data class ReactionCountDetails(
    val burst: Int,
    val normal: Int,
)
