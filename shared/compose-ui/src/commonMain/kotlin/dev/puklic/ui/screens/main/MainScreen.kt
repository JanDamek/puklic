package dev.puklic.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.puklic.ui.components.EmptyState
import dev.puklic.ui.theme.LocalPuklicSpacing

/**
 * Three-pane Expanded layout per `docs/04_ui/adaptive-layouts.md`:
 *  [GuildRail 56dp] | [ChannelListPane 240dp] | [MessagePane rest]
 *
 * Phase 1 renders placeholder content in each pane — wiring to live data lands in step 15-16.
 */
@Composable
public fun MainScreen(viewModel: MainViewModel) {
    Row(modifier = Modifier.fillMaxSize()) {
        GuildRail(modifier = Modifier.width(56.dp).fillMaxHeight())
        VerticalDivider()
        ChannelListPane(modifier = Modifier.width(240.dp).fillMaxHeight())
        VerticalDivider()
        MessagePane(modifier = Modifier.fillMaxHeight().fillMaxWidth())
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun GuildRail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ChannelListPane(modifier: Modifier = Modifier) {
    val spacing = LocalPuklicSpacing.current
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(spacing.space4),
    ) {
        Text("Server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.space4))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(spacing.space4))
        Text(
            "No channels yet",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessagePane(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        EmptyState(
            title = "Select a channel to start chatting",
            body = "Channels appear here once you join a server.",
        )
    }
}
