package dev.puklic.ui.resolvers

import androidx.compose.runtime.staticCompositionLocalOf
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.UserSummary
import dev.puklic.domain.Channel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Resolves mention targets to a renderable display form. Implementations live in higher
 * layers (the desktop/Android app composition) and are provided via [LocalMentionResolver].
 *
 * Per `docs/02_domain/richtext-ast.md`, resolution is reactive — display state can change
 * (e.g. when nickname updates arrive via the gateway).
 */
public interface MentionResolver {
    public fun resolveUser(id: UserId): Flow<UserSummary?>
    public fun resolveRole(id: RoleId): Flow<RoleDisplay?>
    public fun resolveChannel(id: ChannelId): Flow<Channel?>
}

public data class RoleDisplay(val name: String, val colorArgb: Int?)

public object NoopMentionResolver : MentionResolver {
    override fun resolveUser(id: UserId): Flow<UserSummary?> = flowOf(null)
    override fun resolveRole(id: RoleId): Flow<RoleDisplay?> = flowOf(null)
    override fun resolveChannel(id: ChannelId): Flow<Channel?> = flowOf(null)
}

public val LocalMentionResolver: androidx.compose.runtime.ProvidableCompositionLocal<MentionResolver> =
    staticCompositionLocalOf { NoopMentionResolver }

/**
 * Resolves [EmojiRef]s to a renderable form. Unicode emoji render as their codepoint string;
 * custom emoji resolve to an asset URL on the Discord CDN.
 */
public interface EmojiResolver {
    public fun resolve(ref: EmojiRef): EmojiDisplay
}

public sealed interface EmojiDisplay {
    public data class Unicode(val codepoint: String) : EmojiDisplay
    public data class Custom(val name: String, val url: String?) : EmojiDisplay
}

public object NoopEmojiResolver : EmojiResolver {
    override fun resolve(ref: EmojiRef): EmojiDisplay = when (ref) {
        is EmojiRef.Unicode -> EmojiDisplay.Unicode(ref.codepoint)
        is EmojiRef.Custom -> EmojiDisplay.Custom(name = ref.name, url = null)
    }
}

/**
 * [EmojiResolver] that computes the CDN URL directly from the emoji ID (no DB lookup
 * required — Discord stores all custom emoji on `cdn.discordapp.com/emojis/{id}`).
 */
public object CdnEmojiResolver : EmojiResolver {
    private const val SIZE = 32
    override fun resolve(ref: EmojiRef): EmojiDisplay = when (ref) {
        is EmojiRef.Unicode -> EmojiDisplay.Unicode(ref.codepoint)
        is EmojiRef.Custom -> EmojiDisplay.Custom(
            name = ref.name,
            url = "https://cdn.discordapp.com/emojis/${ref.id.value}.${if (ref.animated) "gif" else "png"}" +
                "?size=$SIZE&quality=lossless",
        )
    }
}

public val LocalEmojiResolver: androidx.compose.runtime.ProvidableCompositionLocal<EmojiResolver> =
    staticCompositionLocalOf { NoopEmojiResolver }
