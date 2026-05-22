package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.domain.Channel
import dev.puklic.ui.theme.LocalPuklicSpacing

@Composable
public fun ChannelListItem(
    channel: Channel,
    isSelected: Boolean,
    isMuted: Boolean = false,
    unreadCount: Int = 0,
    mentionCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val background = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val textColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val opacity = if (isMuted) 0.5f else 1f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "# ${channel.name.orEmpty()}",
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
        if (mentionCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.error) { Text(mentionCount.toString()) }
        } else if (unreadCount > 0) {
            Badge { Text(unreadCount.toString()) }
        }
    }
    @Suppress("UNUSED_EXPRESSION") opacity
}
