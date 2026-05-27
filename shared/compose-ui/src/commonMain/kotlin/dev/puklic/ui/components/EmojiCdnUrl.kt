package dev.puklic.ui.components

import dev.puklic.domain.EmojiRef

/**
 * Builder for Discord custom-emoji CDN URLs.
 *
 * Discord serves custom emoji at:
 *   - Static  : `https://cdn.discordapp.com/emojis/<id>.png?size=<size>&quality=lossless`
 *   - Animated: `https://cdn.discordapp.com/emojis/<id>.gif?size=<size>&quality=lossless`
 *
 * Pure function: no I/O, no Compose dependency. Lives in commonMain so unit tests
 * can pin URL shape without spinning up a renderer. Disk caching of the responses
 * is configured globally on the Coil [coil3.ImageLoader] at desktop startup — see
 * `desktop/app/.../Main.kt`. This builder only emits the canonical URL string.
 */
public object EmojiCdnUrl {
    /** Default rendered pixel size requested from the CDN (matches `EmojiSize` in RichTextView). */
    public const val DEFAULT_SIZE_PX: Int = 32

    /** Discord CDN host. Single SSOT — never inlined. */
    private const val CDN_BASE: String = "https://cdn.discordapp.com/emojis"

    private const val EXT_STATIC: String = "png"
    private const val EXT_ANIMATED: String = "gif"

    /**
     * Build the CDN URL for a custom emoji.
     *
     * @param id snowflake of the emoji
     * @param animated `true` → `.gif`, `false` → `.png`
     * @param size requested pixel size (must be > 0). Discord supports 16/32/64/128/256/512/1024;
     *             other values are clamped server-side. We do not pre-validate — pass the visual
     *             target and let the CDN snap.
     */
    public fun build(id: Long, animated: Boolean, size: Int = DEFAULT_SIZE_PX): String {
        require(size > 0) { "size must be positive, got $size" }
        val ext = if (animated) EXT_ANIMATED else EXT_STATIC
        return "$CDN_BASE/$id.$ext?size=$size&quality=lossless"
    }

    /** Convenience overload accepting an [EmojiRef.Custom]. */
    public fun build(ref: EmojiRef.Custom, size: Int = DEFAULT_SIZE_PX): String =
        build(id = ref.id.value, animated = ref.animated, size = size)
}
