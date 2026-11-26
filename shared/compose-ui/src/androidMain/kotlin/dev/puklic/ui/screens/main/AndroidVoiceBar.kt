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
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.voice_bar_connecting_to
import dev.puklic.ui.resources.voice_bar_title_fallback
import dev.puklic.ui.resources.voice_bar_title_with_server
import dev.puklic.ui.resources.voice_cancel_call
import dev.puklic.ui.resources.voice_deafen
import dev.puklic.ui.resources.voice_disconnect
import dev.puklic.ui.resources.voice_dismiss
import dev.puklic.ui.resources.voice_mute_microphone
import dev.puklic.ui.resources.voice_status_failed
import dev.puklic.ui.resources.voice_undeafen
import dev.puklic.ui.resources.voice_unmute_microphone
import org.jetbrains.compose.resources.stringResource
import dev.puklic.ui.text.asString

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
            description = stringResource(
                if (state.selfMute) Res.string.voice_unmute_microphone else Res.string.voice_mute_microphone,
            ),
            highlighted = state.selfMute,
            onClick = actions.onMuteToggle,
        )
        BarIconButton(
            icon = if (state.selfDeaf) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
            description = stringResource(if (state.selfDeaf) Res.string.voice_undeafen else Res.string.voice_deafen),
            highlighted = state.selfDeaf,
            onClick = actions.onDeafenToggle,
        )
        BarIconButton(Icons.Filled.CallEnd, stringResource(Res.string.voice_disconnect), highlighted = true, onClick = actions.onDisconnect)
    }
}

@Composable
private fun ConnectingRow(label: VoiceChannelLabel?, onDisconnect: () -> Unit) {
    BarRow {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        BarTitle(stringResource(Res.string.voice_bar_connecting_to, label.titleText()), Modifier.weight(1f))
        BarIconButton(Icons.Filled.CallEnd, stringResource(Res.string.voice_cancel_call), highlighted = true, onClick = onDisconnect)
    }
}

@Composable
private fun FailedRow(reason: String, onDismiss: () -> Unit) {
    BarRow(background = MaterialTheme.colorScheme.errorContainer) {
        BarTitle(stringResource(Res.string.voice_status_failed, reason), Modifier.weight(1f), MaterialTheme.colorScheme.onErrorContainer)
        BarIconButton(Icons.Filled.Close, stringResource(Res.string.voice_dismiss), highlighted = false, onClick = onDismiss)
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

@Composable
private fun VoiceChannelLabel?.titleText(): String = when {
    this == null -> stringResource(Res.string.voice_bar_title_fallback)
    serverName == null -> channelName.asString()
    else -> stringResource(Res.string.voice_bar_title_with_server, channelName.asString(), serverName)
}
