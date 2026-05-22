package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Optional CTA wrapper for [EmptyState]. */
public data class EmptyStateAction(val label: String, val onClick: () -> Unit)

/**
 * Centered empty state per `docs/04_ui/screens.md` §"Empty states". Icon rendering is omitted
 * in Phase 1 — title + body cover the MVP requirement.
 */
@Composable
public fun EmptyState(
    title: String,
    body: String,
    action: EmptyStateAction? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Box(modifier = modifier.fillMaxSize().padding(spacing.space5), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                TextButton(onClick = action.onClick) { Text(action.label) }
            }
        }
    }
}
