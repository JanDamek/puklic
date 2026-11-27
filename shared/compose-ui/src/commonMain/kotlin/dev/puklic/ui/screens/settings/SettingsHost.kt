package dev.puklic.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.ui.resolvers.LocalBackgroundConnection
import dev.puklic.ui.screens.main.MainViewModel
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.settings_category_coming_soon
import org.jetbrains.compose.resources.stringResource

/**
 * Switches between settings category screens. ACCOUNT is always implemented and NOTIFICATIONS where the
 * platform can keep the connection in the background; every other category renders a placeholder.
 *
 * Per architect report v2 §2.
 */
@Composable
public fun SettingsHost(category: SettingsCategory, viewModel: MainViewModel) {
    when (category) {
        SettingsCategory.ACCOUNT -> AccountSettingsScreen(viewModel)
        SettingsCategory.NOTIFICATIONS -> NotificationSettingsHost()
        else -> PlaceholderCategory(category)
    }
}

@Composable
private fun NotificationSettingsHost() {
    val backgroundConnection = LocalBackgroundConnection.current
    if (backgroundConnection.supported) {
        NotificationSettingsScreen(backgroundConnection)
    } else {
        PlaceholderCategory(SettingsCategory.NOTIFICATIONS)
    }
}

@Composable
private fun PlaceholderCategory(category: SettingsCategory) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.settings_category_coming_soon, category.label()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
