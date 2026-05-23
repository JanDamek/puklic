package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.domain.VoiceMember
import dev.puklic.ids.UserId

/**
 * Single member row rendered under a voice-channel entry in the channel list. Shows a small
 * person icon, the resolved display name, and mute/deaf indicators when applicable.
 *
 * [resolveDisplayName] is suspending because user lookup hits the cache repository. The row
 * shows the numeric id as a placeholder during the first composition, then swaps in the real
 * name when the lookup returns.
 */
@Composable
public fun VoiceMemberRow(
    member: VoiceMember,
    resolveDisplayName: suspend (UserId) -> String,
    modifier: Modifier = Modifier,
) {
    var displayName by remember(member.userId) { mutableStateOf(member.userId.value.toString()) }
    LaunchedEffect(member.userId) {
        displayName = resolveDisplayName(member.userId)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (member.selfMute || member.muted) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Outlined.MicOff,
                contentDescription = "Muted",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        if (member.selfDeaf || member.deafened) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Outlined.VolumeOff,
                contentDescription = "Deafened",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
