package dev.puklic.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.platform.BackgroundConnection
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.settings_notifications_background_description
import dev.puklic.ui.resources.settings_notifications_background_title
import dev.puklic.ui.theme.LocalPuklicSpacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** NOTIFICATIONS category: the switch that keeps the connection alive while the app is closed. */
@Composable
internal fun NotificationSettingsScreen(backgroundConnection: BackgroundConnection) {
    val spacing = LocalPuklicSpacing.current
    val enabled by backgroundConnection.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth().padding(spacing.space5),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.space4),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
            Text(
                text = stringResource(Res.string.settings_notifications_background_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.settings_notifications_background_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { requested -> scope.launch { backgroundConnection.setEnabled(requested) } },
        )
    }
}
