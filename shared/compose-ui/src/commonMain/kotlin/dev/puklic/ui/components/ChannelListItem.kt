package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.puklic.domain.Channel
import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.ui.theme.LocalPuklicSpacing

@Composable
public fun ChannelListItem(
    channel: Channel,
    isSelected: Boolean,
    unreadCount: Int = 0,
    mentionCount: Int = 0,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val isUnread = unreadCount > 0 || mentionCount > 0
    val background = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    // Read = muted/regular; unread = bold + full-strength onSurface (issue #91 Slice 2 UX).
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isUnread -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal
    val isVoice = channel is GuildVoiceChannel
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isVoice) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "Voice channel",
                    tint = textColor,
                    modifier = Modifier.height(16.dp),
                )
                Text(
                    text = " ${channel.name.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = fontWeight,
                    color = textColor,
                )
            } else {
                Text(
                    text = "# ${channel.name.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = fontWeight,
                    color = textColor,
                )
            }
        }
        if (mentionCount > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.error) {
                Text(if (mentionCount > 9) "9+" else mentionCount.toString())
            }
        }
    }
}
