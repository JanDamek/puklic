package dev.puklic.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.puklic.ui.theme.LocalPuklicSpacing
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.settings_category_about
import dev.puklic.ui.resources.settings_category_account
import dev.puklic.ui.resources.settings_category_appearance
import dev.puklic.ui.resources.settings_category_keybindings
import dev.puklic.ui.resources.settings_category_notifications
import dev.puklic.ui.resources.settings_category_storage
import dev.puklic.ui.resources.settings_title
import org.jetbrains.compose.resources.stringResource

public enum class SettingsCategory { ACCOUNT, APPEARANCE, NOTIFICATIONS, STORAGE, KEYBINDINGS, ABOUT }

/**
 * Full-screen modal per `docs/04_ui/screens.md` §SettingsOverlay. When [isOpen] is false the
 * overlay is skipped entirely.
 */
@Composable
public fun SettingsOverlay(
    isOpen: Boolean,
    selectedCategory: SettingsCategory,
    onCategorySelect: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!isOpen) return
    val spacing = LocalPuklicSpacing.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onClose),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(spacing.space5)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier
                    .width(240.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(spacing.space4),
            ) {
                Text(stringResource(Res.string.settings_title), style = MaterialTheme.typography.titleMedium)
                SettingsCategory.entries.forEach { category ->
                    Text(
                        text = category.label(),
                        modifier = Modifier
                            .padding(vertical = spacing.space3)
                            .clickable { onCategorySelect(category) },
                        color = if (category == selectedCategory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize().padding(spacing.space6)) {
                content()
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(spacing.space4),
        ) {
            Text("✕", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun SettingsCategory.label(): String = stringResource(
    when (this) {
        SettingsCategory.ACCOUNT -> Res.string.settings_category_account
        SettingsCategory.APPEARANCE -> Res.string.settings_category_appearance
        SettingsCategory.NOTIFICATIONS -> Res.string.settings_category_notifications
        SettingsCategory.STORAGE -> Res.string.settings_category_storage
        SettingsCategory.KEYBINDINGS -> Res.string.settings_category_keybindings
        SettingsCategory.ABOUT -> Res.string.settings_category_about
    },
)
