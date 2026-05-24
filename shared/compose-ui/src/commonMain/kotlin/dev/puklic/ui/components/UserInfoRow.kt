package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.puklic.domain.UserSummary
import dev.puklic.repositories.PresenceState
import dev.puklic.ui.components.voice.DaveLockIcon
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.VoiceState

/**
 * Identity + quick-controls row pinned to the bottom of the channel-list pane. Replaces the
 * orphaned `LogoutBar` (architect report v2 §3).
 *
 * Mic + deafen icons are disabled when [voiceState] is [VoiceState.Idle] — no pending-intent
 * machinery (architect report v2 §3, drops critic M2 complexity).
 */
public data class UserInfoRowState(
    val self: UserSummary?,
    val presence: PresenceState?,
    val voiceState: VoiceState,
    val daveState: DaveUiState,
    val micMuted: Boolean,
    val deafened: Boolean,
)

@Composable
public fun UserInfoRow(
    state: UserInfoRowState,
    onMicToggle: () -> Unit,
    onDeafToggle: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val self = state.self
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (self == null) {
            // READY race: render skeleton instead of crashing. Expected <500ms after route switch.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            )
            Spacer(Modifier.weight(1f))
        } else {
            PuklicAvatar(
                user = self,
                size = 32.dp,
                showPresence = true,
                presence = state.presence,
                ringColor = MaterialTheme.colorScheme.surface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = self.globalName ?: self.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "@${self.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val voiceConnected = state.voiceState is VoiceState.Connected
        IconButton(onClick = onMicToggle, enabled = voiceConnected) {
            Icon(
                imageVector = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (state.micMuted) "Unmute" else "Mute",
                tint = when {
                    !voiceConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.micMuted -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        IconButton(onClick = onDeafToggle, enabled = voiceConnected) {
            Icon(
                imageVector = if (state.deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                contentDescription = if (state.deafened) "Undeafen" else "Deafen",
                tint = when {
                    !voiceConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    state.deafened -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        DaveLockIcon(state.daveState)
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Open settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
