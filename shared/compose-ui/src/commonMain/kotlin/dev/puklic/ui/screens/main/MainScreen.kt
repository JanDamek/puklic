package dev.puklic.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.arkivanov.essenty.lifecycle.destroy
import dev.puklic.repositories.MessageOrchestrator
import dev.puklic.ui.components.Composer
import dev.puklic.ui.components.MessageList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.domain.Channel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildTextChannel
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ui.components.CategoryHeader
import dev.puklic.ui.components.ChannelListItem
import dev.puklic.ui.components.EmptyState
import dev.puklic.ui.components.GuildRailItem
import dev.puklic.ui.theme.LocalPuklicSpacing

/**
 * Three-pane Expanded layout per `docs/04_ui/adaptive-layouts.md`:
 *  [GuildRail 56dp] | [ChannelListPane 240dp] | [MessagePane rest]
 *
 * Renders live guilds + channels from the [MainViewModel] orchestrator-backed state.
 */
@Composable
public fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    Row(modifier = Modifier.fillMaxSize()) {
        GuildRail(
            guilds = state.guilds,
            selectedGuildId = state.selectedGuildId,
            onSelectGuild = viewModel::selectGuild,
            modifier = Modifier.width(56.dp).fillMaxHeight(),
        )
        VerticalDivider()
        ChannelListPane(
            channels = state.channelsForSelectedGuild,
            selectedChannelId = state.selectedChannelId,
            onSelectChannel = viewModel::selectChannel,
            modifier = Modifier.width(240.dp).fillMaxHeight(),
        )
        VerticalDivider()
        MessagePane(
            selectedChannelId = state.selectedChannelId,
            messageOrchestrator = viewModel.orchestrators?.messages,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
        )
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
private fun GuildRail(
    guilds: List<Guild>,
    selectedGuildId: GuildId?,
    onSelectGuild: (GuildId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        guilds.forEach { g ->
            GuildRailItem(
                guild = g,
                isSelected = g.id == selectedGuildId,
                onClick = { onSelectGuild(g.id) },
            )
        }
    }
}

@Composable
private fun ChannelListPane(
    channels: List<Channel>,
    selectedChannelId: ChannelId?,
    onSelectChannel: (ChannelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(spacing.space4)) {
        Text("Channels", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(spacing.space4))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(spacing.space4))
        if (channels.isEmpty()) {
            Text(
                "No channels yet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CategoryHeader(label = "Text Channels", isExpanded = true, onToggle = {})
            LazyColumn {
                items(channels.filterIsInstance<GuildTextChannel>(), key = { it.id.value }) { ch ->
                    ChannelListItem(
                        channel = ch,
                        isSelected = ch.id == selectedChannelId,
                        onClick = { onSelectChannel(ch.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagePane(
    selectedChannelId: ChannelId?,
    messageOrchestrator: MessageOrchestrator?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        when {
            selectedChannelId == null -> EmptyState(
                title = "Select a channel to start chatting",
                body = "Channels appear here once you join a server.",
            )
            messageOrchestrator == null -> EmptyState(
                title = "No active session",
                body = "Sign in to start receiving messages.",
            )
            else -> ChannelMessages(
                channelId = selectedChannelId,
                orchestrator = messageOrchestrator,
            )
        }
    }
}

@Composable
private fun ChannelMessages(
    channelId: ChannelId,
    orchestrator: MessageOrchestrator,
) {
    // Rebuild ViewModel whenever the selected channel changes. The VM owns a Lifecycle that is
    // resumed for the lifetime of this composition and destroyed in onDispose.
    val viewModel = remember(channelId, orchestrator) {
        val lifecycle = LifecycleRegistry()
        val ctx = DefaultComponentContext(lifecycle = lifecycle)
        lifecycle.resume()
        ChannelMessagesHolder(
            viewModel = MessageListViewModel(ctx, orchestrator, channelId),
            lifecycle = lifecycle,
        )
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.lifecycle.destroy() }
    }
    val state by viewModel.viewModel.state.collectAsState()
    var draft by remember(channelId) { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("#${channelId.value}", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            MessageList(
                state = state,
                onLoadOlder = viewModel.viewModel::loadOlder,
                onMessageAction = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Composer(
            draft = draft,
            placeholder = "Message #${channelId.value}",
            onDraftChange = { draft = it },
            onSubmit = {
                val toSend = draft.trim()
                if (toSend.isNotEmpty()) {
                    viewModel.viewModel.sendMessage(toSend)
                    draft = ""
                }
            },
        )
    }
}

private class ChannelMessagesHolder(
    val viewModel: MessageListViewModel,
    val lifecycle: LifecycleRegistry,
)
