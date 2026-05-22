package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.puklic.domain.UserSummary
import dev.puklic.repositories.PresenceState

/**
 * Avatar per `docs/04_ui/component-library.md` §Avatar.
 *
 * Phase 1: fallback initial only. Coil-loaded avatar URL is deferred until the CDN URL helper
 * lands in step 15-16 (needs `user.avatarHash` → `cdn.discordapp.com/...` mapping).
 */
@Composable
public fun PuklicAvatar(
    user: UserSummary,
    size: Dp = 32.dp,
    showPresence: Boolean = false,
    @Suppress("UnusedParameter") presence: PresenceState? = null,
    modifier: Modifier = Modifier,
) {
    val initial = (user.globalName ?: user.username).firstOrNull()?.uppercase().orEmpty()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Presence dot rendering deferred — needs overlay positioning logic.
    @Suppress("UNUSED_EXPRESSION") showPresence
}
