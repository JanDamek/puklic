package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.puklic.voice.DaveUiState

/**
 * Renders a lock icon reflecting the [DaveUiState] of the current voice session.
 * Closed-lock + green tint when E2EE is active; open-lock otherwise.
 *
 * Moved to commonMain (was in VoiceStatusBar.kt jvmMain) as part of the
 * UserInfoRow refactor (architect report v2 §2, m1).
 */
@Composable
public fun DaveLockIcon(daveState: DaveUiState, modifier: Modifier = Modifier) {
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
        modifier = modifier.size(12.dp),
    )
}
