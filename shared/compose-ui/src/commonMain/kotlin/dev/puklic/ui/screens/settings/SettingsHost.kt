package dev.puklic.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.ui.screens.main.MainViewModel

/**
 * Switches between settings category screens. Currently only ACCOUNT is implemented;
 * every other category renders a Phase-1 placeholder until its own screen lands.
 *
 * Per architect report v2 §2.
 */
@Composable
public fun SettingsHost(category: SettingsCategory, viewModel: MainViewModel) {
    when (category) {
        SettingsCategory.ACCOUNT -> AccountSettingsScreen(viewModel)
        else -> PlaceholderCategory(category)
    }
}

@Composable
private fun PlaceholderCategory(category: SettingsCategory) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "${category.name.lowercase().replaceFirstChar { it.uppercase() }} settings — coming soon",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
