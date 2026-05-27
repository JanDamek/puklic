package dev.puklic.ui.components

import dev.puklic.domain.EmojiRef
import dev.puklic.ids.EmojiId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import kotlin.test.Test

/**
 * Pins the Discord CDN URL shape for custom emoji. The image cache layer (Coil
 * `ImageLoader` disk cache) keys off this exact string, so any change here invalidates
 * the on-disk cache of every existing user — keep the shape stable.
 */
class EmojiCdnUrlTest {
    @Test
    fun `static emoji uses png extension`() {
        EmojiCdnUrl.build(id = 123L, animated = false) shouldBe
            "https://cdn.discordapp.com/emojis/123.png?size=32&quality=lossless"
    }

    @Test
    fun `animated emoji uses gif extension`() {
        EmojiCdnUrl.build(id = 456L, animated = true) shouldBe
            "https://cdn.discordapp.com/emojis/456.gif?size=32&quality=lossless"
    }

    @Test
    fun `size parameter is honoured`() {
        val url = EmojiCdnUrl.build(id = 7L, animated = false, size = 128)
        url shouldContain "size=128"
        url shouldStartWith "https://cdn.discordapp.com/emojis/7."
    }

    @Test
    fun `custom ref overload preserves animated flag and id`() {
        val ref = EmojiRef.Custom(EmojiId(999L), name = "pepega", animated = true)
        val url = EmojiCdnUrl.build(ref)
        url shouldContain "/999.gif"
        url shouldEndWith "?size=32&quality=lossless"
    }

    @Test
    fun `non-positive size is rejected`() {
        shouldThrow<IllegalArgumentException> { EmojiCdnUrl.build(id = 1L, animated = false, size = 0) }
        shouldThrow<IllegalArgumentException> { EmojiCdnUrl.build(id = 1L, animated = false, size = -1) }
    }

    @Test
    fun `default display size constant matches in-renderer expectation`() {
        // RichTextView renders custom emoji at 22 dp; we request 32 px from the CDN
        // (next supported step up). Pin to detect accidental drift.
        EmojiCdnUrl.DEFAULT_SIZE_PX shouldBe 32
    }
}
