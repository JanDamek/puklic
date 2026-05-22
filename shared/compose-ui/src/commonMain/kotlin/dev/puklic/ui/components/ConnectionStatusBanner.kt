package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Banner connection state per `docs/04_ui/component-library.md` §ConnectionStatusBanner. */
public sealed interface ConnectionState {
    public data object Connected : ConnectionState
    public data object Connecting : ConnectionState
    public data class Reconnecting(val secondsUntilRetry: Int) : ConnectionState
    public data object Offline : ConnectionState
    public data object TokenExpired : ConnectionState
}

@Composable
public fun ConnectionStatusBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    onSignInAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is ConnectionState.Connected) return
    val spacing = LocalPuklicSpacing.current
    val (background, label, action) = when (state) {
        ConnectionState.Connecting -> Triple(MaterialTheme.colorScheme.surfaceVariant, "Connecting to Discord…", null)
        is ConnectionState.Reconnecting -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            "Reconnecting in ${state.secondsUntilRetry}s…",
            "Retry now" to onRetry,
        )
        ConnectionState.Offline -> Triple(
            MaterialTheme.colorScheme.error,
            "Offline — read-only mode. Messages will send when you reconnect.",
            null,
        )
        ConnectionState.TokenExpired -> Triple(
            MaterialTheme.colorScheme.error,
            "Session expired.",
            "Sign in again" to onSignInAgain,
        )
        ConnectionState.Connected -> return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = spacing.space4, vertical = spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        if (action != null) {
            TextButton(onClick = action.second) { Text(action.first) }
        }
    }
}
