package dev.puklic.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.puklic.voice.VoiceState

/** User actions offered by the voice bar. */
internal class VoiceBarActions(
    val onMuteToggle: () -> Unit,
    val onDeafenToggle: () -> Unit,
    val onDisconnect: () -> Unit,
)

private val ConnectedDot = Color(0xFF22C55E)

/** Single-row voice bar; renders nothing while the client is idle. */
@Composable
internal fun AndroidVoiceBar(state: VoiceState, label: VoiceChannelLabel?, actions: VoiceBarActions) {
    when (state) {
        is VoiceState.Idle -> Unit
        is VoiceState.Connecting, is VoiceState.Ringing -> ConnectingRow(label, actions.onDisconnect)
        is VoiceState.Connected -> ConnectedRow(state, label, actions)
        is VoiceState.Failed -> FailedRow(state.reason, actions.onDisconnect)
    }
}

@Composable
private fun ConnectedRow(state: VoiceState.Connected, label: VoiceChannelLabel?, actions: VoiceBarActions) {
    BarRow {
        Box(modifier = Modifier.size(8.dp).background(ConnectedDot, CircleShape))
        BarTitle(label.titleText(), Modifier.weight(1f))
        BarIconButton(
            icon = if (state.selfMute) Icons.Filled.MicOff else Icons.Filled.Mic,
            description = if (state.selfMute) "Unmute microphone" else "Mute microphone",
            highlighted = state.selfMute,
            onClick = actions.onMuteToggle,
        )
        BarIconButton(
            icon = if (state.selfDeaf) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
            description = if (state.selfDeaf) "Undeafen" else "Deafen",
            highlighted = state.selfDeaf,
            onClick = actions.onDeafenToggle,
        )
        BarIconButton(Icons.Filled.CallEnd, "Disconnect", highlighted = true, onClick = actions.onDisconnect)
    }
}

@Composable
private fun ConnectingRow(label: VoiceChannelLabel?, onDisconnect: () -> Unit) {
    BarRow {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        BarTitle("Connecting to ${label.titleText()}", Modifier.weight(1f))
        BarIconButton(Icons.Filled.CallEnd, "Cancel call", highlighted = true, onClick = onDisconnect)
    }
}

@Composable
private fun FailedRow(reason: String, onDismiss: () -> Unit) {
    BarRow(background = MaterialTheme.colorScheme.errorContainer) {
        BarTitle("Voice failed: $reason", Modifier.weight(1f), MaterialTheme.colorScheme.onErrorContainer)
        BarIconButton(Icons.Filled.Close, "Dismiss", highlighted = false, onClick = onDismiss)
    }
}

@Composable
private fun BarRow(
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(background).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun BarTitle(text: String, modifier: Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BarIconButton(icon: ImageVector, description: String, highlighted: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (highlighted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun VoiceChannelLabel?.titleText(): String = when {
    this == null -> "Voice"
    serverName == null -> channelName
    else -> "$channelName · $serverName"
}
