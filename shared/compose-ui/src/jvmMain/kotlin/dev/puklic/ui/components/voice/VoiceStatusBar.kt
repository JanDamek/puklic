package dev.puklic.ui.components.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PresentToAll
import androidx.compose.material.icons.outlined.StopScreenShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.puklic.voice.DaveUiState
import dev.puklic.voice.VoiceState
import dev.puklic.voice.screenshare.ScreenShareState

/**
 * Two-row docked voice status widget for the bottom of the channel-list pane.
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §12.
 *
 * - Idle: hidden (returns an empty Box).
 * - Connecting: spinner + label.
 * - Connected: green dot + channel label, then a row of icon buttons
 *   (mic / headphones / settings / hangup).
 * - Failed: red banner with a retry button.
 */
@Composable
public fun VoiceStatusBar(
    state: VoiceState,
    channelLabel: String?,
    onMicToggle: () -> Unit,
    onDeafToggle: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    screenShareState: ScreenShareState,
    onScreenSharePick: () -> Unit,
    onScreenShareStop: () -> Unit,
    modifier: Modifier = Modifier,
    daveState: DaveUiState = DaveUiState.Off,
) {
    when (state) {
        is VoiceState.Idle -> Box(modifier = modifier)
        is VoiceState.Connecting -> ConnectingBar(channelLabel, modifier)
        is VoiceState.Connected -> ConnectedBar(
            state = state,
            channelLabel = channelLabel,
            onMicToggle = onMicToggle,
            onDeafToggle = onDeafToggle,
            onDisconnect = onDisconnect,
            onSettings = onSettings,
            screenShareState = screenShareState,
            onScreenSharePick = onScreenSharePick,
            onScreenShareStop = onScreenShareStop,
            modifier = modifier,
            daveState = daveState,
        )
        is VoiceState.Failed -> FailedBar(state.reason, onRetry, modifier)
    }
}

@Composable
private fun ConnectingBar(channelLabel: String?, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = "Connecting${channelLabel?.let { " to $it" }.orEmpty()}...",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectedBar(
    state: VoiceState.Connected,
    channelLabel: String?,
    onMicToggle: () -> Unit,
    onDeafToggle: () -> Unit,
    onDisconnect: () -> Unit,
    onSettings: () -> Unit,
    screenShareState: ScreenShareState,
    onScreenSharePick: () -> Unit,
    onScreenShareStop: () -> Unit,
    modifier: Modifier,
    daveState: DaveUiState = DaveUiState.Off,
) {
    val sharing = screenShareState is ScreenShareState.Active
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(0xFF22C55E), CircleShape),
            )
            Text(
                text = buildString {
                    append("Voice Connected")
                    channelLabel?.let { append(" - $it") }
                    if (sharing) append(" - Sharing")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (sharing) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DaveLockIcon(daveState)
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onMicToggle) {
                Icon(
                    imageVector = if (state.selfMute) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (state.selfMute) "Unmute" else "Mute",
                    tint = if (state.selfMute) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onDeafToggle) {
                Icon(
                    imageVector = if (state.selfDeaf) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                    contentDescription = if (state.selfDeaf) "Undeafen" else "Deafen",
                    tint = if (state.selfDeaf) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Voice settings",
                )
            }
            IconButton(onClick = if (sharing) onScreenShareStop else onScreenSharePick) {
                Icon(
                    imageVector = if (sharing) Icons.Outlined.StopScreenShare
                    else Icons.Outlined.PresentToAll,
                    contentDescription = if (sharing) "Stop screen share" else "Share screen",
                    tint = if (sharing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onDisconnect) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "Disconnect",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DaveLockIcon(daveState: DaveUiState) {
    val active = daveState is DaveUiState.Active
    val description = when (daveState) {
        is DaveUiState.Active -> "End-to-end encrypted (DAVE epoch ${daveState.epoch})"
        is DaveUiState.Connecting -> "DAVE handshake in progress"
        is DaveUiState.Disabled -> "DAVE disabled: ${daveState.reason}"
        DaveUiState.Off -> "Not end-to-end encrypted"
    }
    Icon(
        imageVector = if (active) Icons.Filled.Lock else Icons.Filled.LockOpen,
        contentDescription = description,
        tint = if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(12.dp),
    )
}

@Composable
private fun FailedBar(reason: String, onRetry: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Voice failed: $reason",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(end = 8.dp),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}
