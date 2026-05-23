package dev.puklic.ui.components.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.voice.AudioDevice

/**
 * Voice settings dialog: pick capture + playback device.
 *
 * Per architect report §12. Input-volume slider is deferred.
 */
@Composable
public fun VoiceSettingsDialog(
    devices: List<AudioDevice>,
    selectedCaptureId: String?,
    selectedPlaybackId: String?,
    onSelectCapture: (String) -> Unit,
    onSelectPlayback: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val captureDevices = remember(devices) {
        devices.filter { it.direction == AudioDevice.Direction.Capture }
    }
    val playbackDevices = remember(devices) {
        devices.filter { it.direction == AudioDevice.Direction.Playback }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Input device", style = MaterialTheme.typography.labelMedium)
                DeviceDropdown(
                    devices = captureDevices,
                    selectedId = selectedCaptureId
                        ?: captureDevices.firstOrNull { it.isDefault }?.id
                        ?: captureDevices.firstOrNull()?.id,
                    onSelect = onSelectCapture,
                )
                Text("Output device", style = MaterialTheme.typography.labelMedium)
                DeviceDropdown(
                    devices = playbackDevices,
                    selectedId = selectedPlaybackId
                        ?: playbackDevices.firstOrNull { it.isDefault }?.id
                        ?: playbackDevices.firstOrNull()?.id,
                    onSelect = onSelectPlayback,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun DeviceDropdown(
    devices: List<AudioDevice>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = devices.firstOrNull { it.id == selectedId }?.displayName
        ?: "(no device available)"
    OutlinedButton(
        onClick = { expanded = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        devices.forEach { d ->
            DropdownMenuItem(
                text = {
                    Row(modifier = Modifier.padding(end = 12.dp).clickable { /* row click bound below */ }) {
                        Text(d.displayName)
                    }
                },
                onClick = {
                    onSelect(d.id)
                    expanded = false
                },
            )
        }
    }
}
