package dev.puklic.ui.components.voice

import androidx.compose.foundation.clickable
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
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.voice_dave_active
import dev.puklic.ui.resources.voice_dave_connecting
import dev.puklic.ui.resources.voice_dave_disabled
import dev.puklic.ui.resources.voice_dave_off
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a lock icon reflecting the [DaveUiState] of the current voice session.
 * Closed-lock + green tint when E2EE is active; open-lock otherwise.
 *
 * Moved to commonMain (was in VoiceStatusBar.kt jvmMain) as part of the
 * UserInfoRow refactor (architect report v2 §2, m1).
 */
@Composable
public fun DaveLockIcon(
    daveState: DaveUiState,
    modifier: Modifier = Modifier,
    onVerifyCall: (() -> Unit)? = null,
) {
    val active = daveState is DaveUiState.Active
    val description = when (daveState) {
        is DaveUiState.Active -> stringResource(Res.string.voice_dave_active, daveState.epoch.toString())
        is DaveUiState.Connecting -> stringResource(Res.string.voice_dave_connecting)
        is DaveUiState.Disabled -> stringResource(Res.string.voice_dave_disabled, daveState.reason)
        DaveUiState.Off -> stringResource(Res.string.voice_dave_off)
    }
    val clickModifier = if (active && onVerifyCall != null) {
        Modifier.clickable(onClick = onVerifyCall)
    } else {
        Modifier
    }
    Icon(
        imageVector = if (active) Icons.Filled.Lock else Icons.Filled.LockOpen,
        contentDescription = description,
        tint = if (active) Color(0xFF22C55E) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.then(clickModifier).size(LOCK_ICON_SIZE_DP.dp),
    )
}

private const val LOCK_ICON_SIZE_DP: Int = 12
