package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.Guild

private const val CDN_BASE = "https://cdn.discordapp.com"
private const val ICON_SIZE_PX = 64

/**
 * Builds the CDN URL for a guild icon. Animated icons have hashes prefixed with `a_`; we
 * still request `.png` (Coil decodes the static frame) to keep the rail lightweight. Returns
 * `null` when the guild has no custom icon — caller renders the letter fallback.
 */
internal fun guildIconUrl(guildId: Long, iconHash: String?): String? {
    if (iconHash == null) return null
    return "$CDN_BASE/icons/$guildId/$iconHash.png?size=$ICON_SIZE_PX"
}

@Composable
public fun GuildRailItem(
    guild: Guild,
    isSelected: Boolean,
    @Suppress("UnusedParameter") hasUnread: Boolean = false,
    @Suppress("UnusedParameter") mentionCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = guildIconUrl(guild.id.value, guild.iconHash)
    val selectionRing = if (isSelected) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(selectionRing)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = guild.name,
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = guild.name.firstOrNull()?.uppercase().orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
