package dev.puklic.ui.components.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Persistent banner shown when DAVE end-to-end encryption is lost mid-call
 * (state transitioned `Active → Disabled` or `Active → Off`, see
 * [dev.puklic.voice.DaveDowngradeDetector]).
 *
 * Auto-hides after [DOWNGRADE_BANNER_AUTO_HIDE_MS] ms or on user dismiss —
 * whichever fires first. Dismiss state is held by the caller (typically the
 * VoiceDock) so re-entering a call resets it.
 */
@Composable
public fun DaveDowngradeBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = BANNER_HPAD_DP.dp, vertical = BANNER_VPAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BANNER_GAP_DP.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Encryption downgrade",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Text(
            text = "End-to-end encryption disabled. Your audio is no longer E2EE protected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/**
 * Time in milliseconds after which the downgrade banner auto-hides if the
 * user has not dismissed it manually. 30 seconds is long enough to be seen
 * during a call but short enough that the user is not stuck with a stale
 * banner after acknowledging out-of-band.
 */
public const val DOWNGRADE_BANNER_AUTO_HIDE_MS: Long = 30_000L

private const val BANNER_HPAD_DP: Int = 12
private const val BANNER_VPAD_DP: Int = 8
private const val BANNER_GAP_DP: Int = 8
