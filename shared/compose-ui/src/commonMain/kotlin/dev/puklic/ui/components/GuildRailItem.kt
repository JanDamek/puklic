package dev.puklic.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import dev.puklic.domain.Guild

@Composable
public fun GuildRailItem(
    guild: Guild,
    isSelected: Boolean,
    @Suppress("UnusedParameter") hasUnread: Boolean = false,
    @Suppress("UnusedParameter") mentionCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = guild.name.firstOrNull()?.uppercase().orEmpty(),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
