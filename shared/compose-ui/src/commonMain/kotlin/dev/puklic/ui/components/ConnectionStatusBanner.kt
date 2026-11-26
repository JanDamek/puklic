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
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.connection_banner_connecting
import dev.puklic.ui.resources.connection_banner_offline
import dev.puklic.ui.resources.connection_banner_reconnecting
import dev.puklic.ui.resources.connection_banner_retry_now
import dev.puklic.ui.resources.connection_banner_session_expired
import dev.puklic.ui.resources.connection_banner_sign_in_again
import org.jetbrains.compose.resources.stringResource

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
        ConnectionState.Connecting -> Triple(MaterialTheme.colorScheme.surfaceVariant, stringResource(Res.string.connection_banner_connecting), null)
        is ConnectionState.Reconnecting -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            stringResource(Res.string.connection_banner_reconnecting, state.secondsUntilRetry.toString()),
            stringResource(Res.string.connection_banner_retry_now) to onRetry,
        )
        ConnectionState.Offline -> Triple(
            MaterialTheme.colorScheme.error,
            stringResource(Res.string.connection_banner_offline),
            null,
        )
        ConnectionState.TokenExpired -> Triple(
            MaterialTheme.colorScheme.error,
            stringResource(Res.string.connection_banner_session_expired),
            stringResource(Res.string.connection_banner_sign_in_again) to onSignInAgain,
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
