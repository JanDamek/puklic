package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.UserSummary
import dev.puklic.repositories.PresenceState
import dev.puklic.ui.theme.LocalPuklicColors

private const val CDN_BASE = "https://cdn.discordapp.com"
private const val AVATAR_SIZE_PX = 64
private const val DEFAULT_EMBED_VARIANTS_POMELO = 6
private const val DEFAULT_EMBED_VARIANTS_LEGACY = 5
private const val POMELO_SHIFT = 22

/** Presence dot diameter as a fraction of avatar diameter (Discord ≈ 32%). */
private const val PRESENCE_DOT_RATIO = 0.32f

/** Surface ring thickness as a fraction of the dot diameter. */
private const val PRESENCE_RING_RATIO = 0.18f

/**
 * Builds the Discord CDN URL for a user's avatar, falling back to the default embed avatar
 * when the user has no custom avatar. Default index follows Discord's pomelo (username-only)
 * formula `(user_id >> 22) % 6` and falls back to `discriminator % 5` for legacy accounts.
 */
internal fun userAvatarUrl(user: UserSummary): String {
    val hash = user.avatarHash
    if (hash != null) {
        return "$CDN_BASE/avatars/${user.id.value}/$hash.png?size=$AVATAR_SIZE_PX"
    }
    val legacyDiscriminator = user.discriminator?.toIntOrNull()?.takeIf { it != 0 }
    val index = if (legacyDiscriminator != null) {
        legacyDiscriminator % DEFAULT_EMBED_VARIANTS_LEGACY
    } else {
        ((user.id.value ushr POMELO_SHIFT) % DEFAULT_EMBED_VARIANTS_POMELO).toInt()
    }
    return "$CDN_BASE/embed/avatars/$index.png"
}

/**
 * Avatar per `docs/04_ui/component-library.md` §Avatar. Renders the Discord CDN image via Coil,
 * falling back to the user's initial only if Coil fails to fetch (the placeholder shows during
 * load; the embed/avatars URL is always non-null so we never need a permanent letter fallback).
 *
 * When [showPresence] is true and [presence] is non-null, a Discord-style presence dot is
 * overlaid in the bottom-right corner with a [ringColor] cutout matching the surrounding
 * surface (see architect report v2 §7).
 */
@Composable
public fun PuklicAvatar(
    user: UserSummary,
    size: Dp = 32.dp,
    showPresence: Boolean = false,
    presence: PresenceState? = null,
    ringColor: Color = MaterialTheme.colorScheme.surface,
    modifier: Modifier = Modifier,
) {
    if (!showPresence || presence == null) {
        AvatarCircle(user = user, size = size, modifier = modifier)
        return
    }
    val dotSize = size * PRESENCE_DOT_RATIO
    val ring = dotSize * PRESENCE_RING_RATIO
    val dotColor = presence.toDotColor()
    Box(modifier = modifier.size(size)) {
        AvatarCircle(user = user, size = size, modifier = Modifier)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(dotSize)
                .background(ringColor, CircleShape)
                .padding(ring)
                .background(dotColor, CircleShape),
        )
    }
}

@Composable
private fun AvatarCircle(user: UserSummary, size: Dp, modifier: Modifier) {
    val initial = (user.globalName ?: user.username).firstOrNull()?.uppercase().orEmpty()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(
            model = userAvatarUrl(user),
            contentDescription = user.globalName ?: user.username,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
internal fun PresenceState.toDotColor(): Color {
    val c = LocalPuklicColors.current
    return when (this) {
        PresenceState.ONLINE -> c.presenceOnline
        PresenceState.IDLE -> c.presenceIdle
        PresenceState.DO_NOT_DISTURB -> c.presenceDnd
        PresenceState.OFFLINE, PresenceState.INVISIBLE -> c.presenceOffline
    }
}
