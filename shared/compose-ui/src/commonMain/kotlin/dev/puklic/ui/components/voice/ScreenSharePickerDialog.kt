package dev.puklic.ui.components.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Window
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.puklic.voice.screenshare.ScreenSource

/**
 * Modal "Share Screen" picker. See architect report
 * `docs/03_infrastructure/architect-reports/2026-05-23-screenshare.md` §9 (UI).
 *
 * Lists [sources] as cards in an adaptive grid. The user selects one and clicks Share, which
 * invokes [onShare] with the chosen source and the "share system audio" flag. When
 * [audioShareSupported] is false the audio checkbox is disabled and [audioShareUnsupportedReason]
 * is shown as helper text + tooltip explaining why.
 *
 * Thumbnails are out of scope in v1 — each card shows a desktop icon plus the display name.
 */
@Composable
public fun ScreenSharePickerDialog(
    sources: List<ScreenSource>,
    audioShareSupported: Boolean,
    audioShareUnsupportedReason: String?,
    onShare: (ScreenSource, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val monitors = remember(sources) { sources.filterIsInstance<ScreenSource.Monitor>() }
    val windows = remember(sources) { sources.filterIsInstance<ScreenSource.Window>() }
    var selectedTab by remember { mutableStateOf(if (monitors.isNotEmpty()) 0 else 1) }
    var selected by remember { mutableStateOf<ScreenSource?>(sources.firstOrNull()) }
    var shareAudio by remember { mutableStateOf(false) }

    val visibleSources = if (selectedTab == 0) monitors else windows

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sources.isEmpty()) {
                    Text(
                        text = "No capturable screens were detected.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Screens (${monitors.size})") },
                            icon = {
                                Icon(Icons.Outlined.DesktopWindows, contentDescription = null)
                            },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Windows (${windows.size})") },
                            icon = {
                                Icon(Icons.Outlined.Window, contentDescription = null)
                            },
                        )
                    }
                    if (selectedTab == 1 && windows.isEmpty()) {
                        Text(
                            text = "No windows detected. Grant Automation permission in System " +
                                "Settings → Privacy & Security → Automation if prompted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedTab == 1) {
                        Text(
                            text = "Window capture currently falls back to fullscreen of the " +
                                "primary monitor (v1 limitation).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(GRID_CELL_MIN_DP.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = GRID_MAX_HEIGHT_DP.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleSources, key = { it.id }) { source ->
                            SourceCard(
                                source = source,
                                selected = selected?.id == source.id,
                                onClick = { selected = source },
                            )
                        }
                    }
                }
                AudioRow(
                    enabled = audioShareSupported,
                    checked = shareAudio && audioShareSupported,
                    onCheckedChange = { shareAudio = it },
                    unsupportedReason = audioShareUnsupportedReason,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    val s = selected ?: return@TextButton
                    onShare(s, shareAudio && audioShareSupported)
                },
            ) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SourceCard(
    source: ScreenSource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CARD_MIN_HEIGHT_DP.dp)
            .selectable(selected = selected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (source is ScreenSource.Window) Icons.Outlined.Window
                else Icons.Outlined.DesktopWindows,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source is ScreenSource.Window) {
                Text(
                    text = source.appName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudioRow(
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    unsupportedReason: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = if (!enabled && unsupportedReason != null) {
                Modifier.semantics { contentDescription = unsupportedReason }
            } else {
                Modifier
            },
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
            Text(
                text = "Share system audio",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
            )
        }
        if (!enabled && unsupportedReason != null) {
            Text(
                text = unsupportedReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private const val GRID_CELL_MIN_DP = 160
private const val GRID_MAX_HEIGHT_DP = 360
private const val CARD_MIN_HEIGHT_DP = 100
private const val DISABLED_ALPHA = 0.5f
