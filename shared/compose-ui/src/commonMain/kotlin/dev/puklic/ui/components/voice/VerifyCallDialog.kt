package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.puklic.ids.UserId

/**
 * Renders a row per remote [participants] participant with the Short
 * Authentication String (SAS) for the pairwise DAVE fingerprint between
 * the local user and that participant.
 *
 * Users compare these SAS strings out-of-band (over the same voice call,
 * out loud) against what the other endpoint shows — a match confirms the
 * MLS handshake was not MITM'd by Discord (or an attacker). A mismatch
 * means the E2EE binding is broken; the call should be ended.
 *
 * SAS fetch is async per participant: each row goes Loading → Loaded(sas)
 * or Loaded(null) when the backend cannot produce one (e.g. participant
 * not yet in MLS group). All fetches are launched in [LaunchedEffect] on
 * the dialog open and cached for the dialog's lifetime — re-opening
 * refetches (group state may have advanced).
 */
@Composable
public fun VerifyCallDialog(
    participants: Set<UserId>,
    sasResolver: suspend (UserId) -> String?,
    displayNameResolver: (UserId) -> String = { "User ${it.value}" },
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(participants) {
        mutableStateOf(participants.associateWith<UserId, SasRowState> { SasRowState.Loading })
    }
    LaunchedEffect(participants) {
        participants.forEach { uid ->
            val sas = runCatching { sasResolver(uid) }.getOrNull()
            rows.value = rows.value + (uid to SasRowState.Loaded(sas))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify call") },
        text = {
            Column(modifier = modifier.fillMaxWidth()) {
                Text(
                    text = "Read these codes aloud and confirm the other side sees the same. " +
                        "A mismatch means the encryption may be compromised.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(top = SECTION_GAP_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP_DP.dp),
                ) {
                    if (participants.isEmpty()) {
                        item {
                            Text(
                                text = "No remote participants in the call yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        items(participants.toList()) { uid ->
                            VerifyCallRow(
                                displayName = displayNameResolver(uid),
                                state = rows.value[uid] ?: SasRowState.Loading,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}

@Composable
private fun VerifyCallRow(displayName: String, state: SasRowState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(modifier = Modifier.padding(start = SECTION_GAP_DP.dp)) {
            when (state) {
                SasRowState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(SPINNER_SIZE_DP.dp),
                    strokeWidth = SPINNER_STROKE_DP.dp,
                )
                is SasRowState.Loaded -> Text(
                    text = state.sas ?: "unavailable",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = if (state.sas == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

private sealed interface SasRowState {
    data object Loading : SasRowState
    data class Loaded(val sas: String?) : SasRowState
}

private const val SECTION_GAP_DP: Int = 12
private const val ROW_GAP_DP: Int = 8
private const val SPINNER_SIZE_DP: Int = 14
private const val SPINNER_STROKE_DP: Int = 2
