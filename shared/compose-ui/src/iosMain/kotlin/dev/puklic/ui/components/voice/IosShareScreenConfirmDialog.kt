package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

/**
 * iOS share-screen confirm dialog, locked design per HARD RULE #3 user approval 2026-05-29.
 *
 * UX:
 *  - Title: "Share your iPhone screen?"
 *  - Body: "The broadcast will capture everything on your screen, including other apps."
 *  - Switch: "Share app audio" — DEFAULT ON
 *  - Cancel: secondary TextButton
 *  - Start broadcast: primary action — a [BroadcastPickerHost] styled as a Material3
 *    `FilledButton` that internally hosts Apple's `RPSystemBroadcastPickerView` (the only
 *    surface allowed to start a ReplayKit broadcast).
 *
 * The audio toggle value is captured into [onStart] when the user taps the picker. The
 * caller wires that into `voiceClient.screenShare.start(source, shareAudio = ...)`.
 *
 * Apple's broadcast sheet then appears with the Puklic extension pre-selected; on user
 * confirm the extension starts and the host app receives frames via
 * `BroadcastExtensionBridge` (FP-11). The dialog is dismissed by the host the moment the
 * picker is tapped — the user-visible "Sharing" indicator is owned by the existing
 * [VoiceStatusBar] driven by `voiceClient.screenShare.state`.
 */
@Composable
public fun IosShareScreenConfirmDialog(
    onStart: (shareAudio: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var shareAudio by remember { mutableStateOf(SHARE_AUDIO_DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share your iPhone screen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "The broadcast will capture everything on your screen, " +
                        "including other apps.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Share app audio",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = shareAudio,
                        onCheckedChange = { shareAudio = it },
                    )
                }
            }
        },
        confirmButton = {
            BroadcastPickerHost(
                primaryContainerColor = MaterialTheme.colorScheme.primary,
                primaryContentColor = MaterialTheme.colorScheme.onPrimary,
                cornerRadiusDp = PRIMARY_BUTTON_CORNER_DP,
                onUserTapped = { onStart(shareAudio) },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val SHARE_AUDIO_DEFAULT: Boolean = true
private const val PRIMARY_BUTTON_CORNER_DP: Int = 20
