package dev.puklic.ui.screens.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.puklic.domain.Channel
import dev.puklic.domain.Guild
import dev.puklic.domain.UserSummary

public data class KeyShortcut(val display: String)

public sealed interface CommandPaletteResult {
    public data class ChannelResult(val channel: Channel) : CommandPaletteResult
    public data class UserResult(val user: UserSummary) : CommandPaletteResult
    public data class GuildResult(val guild: Guild) : CommandPaletteResult
    public data class Command(val id: String, val label: String, val shortcut: KeyShortcut?) : CommandPaletteResult
}

public data class CommandPaletteState(
    val query: String = "",
    val results: List<CommandPaletteResult> = emptyList(),
    val selectedIndex: Int = 0,
)

@Composable
public fun CommandPaletteOverlay(
    isOpen: Boolean,
    state: CommandPaletteState,
    onQueryChange: (String) -> Unit,
    onSelect: (CommandPaletteResult) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!isOpen) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 96.dp)
                .size(width = 640.dp, height = 480.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(enabled = false) {}
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search channels, users, commands…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (state.results.isEmpty()) {
                Text(
                    "No results",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.results.forEachIndexed { index, result ->
                    val highlighted = index == state.selectedIndex
                    Text(
                        text = describe(result),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            )
                            .clickable { onSelect(result) }
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

private fun describe(result: CommandPaletteResult): String = when (result) {
    is CommandPaletteResult.ChannelResult -> "# ${result.channel.name.orEmpty()}"
    is CommandPaletteResult.UserResult -> "@ ${result.user.globalName ?: result.user.username}"
    is CommandPaletteResult.GuildResult -> "👥 ${result.guild.name}"
    is CommandPaletteResult.Command -> "⚡ ${result.label}"
}
