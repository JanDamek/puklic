package dev.puklic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.ui.theme.LocalPuklicSpacing

@Composable
public fun CategoryHeader(
    label: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = (if (isExpanded) "▾ " else "▸ ") + label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
