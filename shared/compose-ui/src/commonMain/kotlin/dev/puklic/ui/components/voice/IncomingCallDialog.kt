package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.puklic.domain.UserSummary
import dev.puklic.ui.components.PuklicAvatar
import dev.puklic.voice.IncomingCall
import kotlinx.coroutines.delay

/** Auto-decline timeout matching Discord's observed ~30s ring window. */
private const val AUTO_DECLINE_MS = 30_000L
private const val GENERIC_TITLE = "Incoming call from this DM"

/**
 * Compose dialog for a single incoming DM voice call. Renders [caller] avatar + name, an
 * "Accept" CTA and a "Decline" outlined button. After [AUTO_DECLINE_MS] the dialog auto-fires
 * [onDecline] unless the user has already accepted.
 *
 * Race-free guarantee: an internal `accepted` flag, keyed to [call], ensures the post-delay
 * branch double-checks user intent and never fires [onDecline] after [onAccept]. Re-keying on
 * [call] cancels the prior timer when a new call replaces it at the queue head.
 *
 * See `docs/03_infrastructure/architect-reports/2026-05-25-dm-incoming-voice.md` §8.
 */
@Composable
public fun IncomingCallDialog(
    call: IncomingCall,
    caller: UserSummary?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    var accepted by remember(call) { mutableStateOf(false) }

    LaunchedEffect(call, accepted) {
        if (!accepted) {
            delay(AUTO_DECLINE_MS)
            if (!accepted) onDecline()
        }
    }

    val title = caller?.let { "${it.globalName ?: it.username} is calling..." } ?: GENERIC_TITLE

    AlertDialog(
        // Require explicit Accept / Decline — scrim taps do not dismiss the ring.
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (caller != null) {
                    PuklicAvatar(user = caller, size = 48.dp)
                }
                Text(
                    text = caller?.let { it.globalName ?: it.username } ?: "Direct message",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    accepted = true
                    onAccept()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("Accept") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDecline,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Decline") }
        },
    )
}
