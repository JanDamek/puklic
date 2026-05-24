package dev.puklic.ui.screens.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
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
import androidx.compose.runtime.rememberCoroutineScope
import dev.puklic.platform.PlatformOpen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.puklic.domain.Channel
import dev.puklic.domain.DmChannel
import dev.puklic.domain.Guild
import dev.puklic.domain.GuildCategoryChannel
import dev.puklic.domain.GuildTextChannel
import dev.puklic.domain.GuildVoiceChannel
import dev.puklic.domain.VoiceMember
import dev.puklic.ids.ChannelId
import dev.puklic.ids.GuildId
import dev.puklic.ids.UserId
import dev.puklic.ui.components.CategoryHeader
import dev.puklic.ui.components.ChannelListItem
import dev.puklic.ui.components.VoiceMemberRow
import dev.puklic.ui.components.EmptyState
import dev.puklic.ui.components.GuildRailItem
import dev.puklic.ui.components.PuklicAvatar
import dev.puklic.ui.components.UserInfoRow
import dev.puklic.ui.components.UserInfoRowState
import dev.puklic.ui.screens.settings.SettingsHost
import dev.puklic.ui.screens.settings.SettingsOverlay
import dev.puklic.ui.theme.LocalPuklicSpacing
import dev.puklic.voice.VoiceState

/**
 * Three-pane Expanded layout per `docs/04_ui/adaptive-layouts.md`:
 *  [GuildRail 56dp] | [ChannelListPane 240dp] | [MessagePane rest]
 *
 * Renders live guilds + channels from the [MainViewModel] orchestrator-backed state.
 */
@Composable
public fun MainScreen(viewModel: MainViewModel, platformOpen: PlatformOpen? = null) {
    val state by viewModel.state.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {
        GuildRail(
            guilds = state.guilds,
            selectedGuildId = state.selectedGuildId,
            isDmHomeSelected = state.isDmHome,
            onSelectDmHome = viewModel::selectDmHome,
            onSelectGuild = viewModel::selectGuild,
            modifier = Modifier.width(56.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Column(modifier = Modifier.width(240.dp).fillMaxHeight()) {
            val paneModifier = Modifier.fillMaxWidth().weight(1f)
            if (state.isDmHome) {
                DmListPane(
                    dms = state.dmChannels,
                    selectedChannelId = state.selectedChannelId,
                    onSelectChannel = viewModel::selectChannel,
                    modifier = paneModifier,
                )
            } else {
                val voiceStates by viewModel.voiceStates.collectAsState()
                ChannelListPane(
                    channels = state.channelsForSelectedGuild,
                    selectedChannelId = state.selectedChannelId,
                    onSelectChannel = viewModel::selectChannel,
                    onJoinVoiceChannel = { guildId, channelId -> viewModel.joinVoiceChannel(guildId, channelId) },
                    voiceMembersByChannel = voiceStates,
                    resolveDisplayName = viewModel::resolveDisplayName,
                    modifier = paneModifier,
                )
            }
            VoiceDock(viewModel = viewModel)
            UserInfoRowMount(viewModel = viewModel)
        }
        VerticalDivider()
        val selectedChannel: Channel? = if (state.isDmHome) {
            state.dmChannels.firstOrNull { it.id == state.selectedChannelId }
        } else {
            state.channelsForSelectedGuild.firstOrNull { it.id == state.selectedChannelId }
        }
        val displayName = when (selectedChannel) {
            is DmChannel -> selectedChannel.recipients.firstOrNull()?.let { "@${it.globalName ?: it.username}" }
            else -> selectedChannel?.name
        }
        val topic = (selectedChannel as? GuildTextChannel)?.topic
        MessagePane(
            selectedChannelId = state.selectedChannelId,
            selectedChannelName = displayName,
            selectedChannelTopic = topic,
            selectedChannel = selectedChannel,
            messageOrchestrator = viewModel.orchestrators?.messages,
            platformOpen = platformOpen,
            onChannelMentionClick = viewModel::selectChannel,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
        )
    }
        val settingsOpen by viewModel.settingsOpen.collectAsState()
        val settingsCategory by viewModel.selectedSettingsCategory.collectAsState()
        SettingsOverlay(
            isOpen = settingsOpen,
            selectedCategory = settingsCategory,
            onCategorySelect = viewModel::selectSettingsCategory,
            onClose = viewModel::closeSettings,
            content = { SettingsHost(category = settingsCategory, viewModel = viewModel) },
        )
    }
}

/**
 * UserInfoRow mount + state assembly per architect report v2 §3. Pinned to the bottom of the
 * channel-list pane just below VoiceDock. Always visible when logged in.
 */
@Composable
private fun UserInfoRowMount(viewModel: MainViewModel) {
    val self by viewModel.selfUser.collectAsState()
    val presences by viewModel.presences.collectAsState()
    val voiceState by viewModel.voiceState.collectAsState()
    val daveState by viewModel.daveState.collectAsState()
    val rowState by remember(viewModel) {
        derivedStateOf {
            val s = self
            val connected = voiceState as? VoiceState.Connected
            UserInfoRowState(
                self = s,
                presence = s?.let { presences[it.id] },
                voiceState = voiceState,
                daveState = daveState,
                micMuted = connected?.selfMute ?: false,
                deafened = connected?.selfDeaf ?: false,
            )
        }
    }
    UserInfoRow(
        state = rowState,
        onMicToggle = viewModel::toggleSelfMute,
        onDeafToggle = viewModel::toggleSelfDeaf,
        onOpenSettings = { viewModel.openSettings(null) },
    )
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
    isDmHomeSelected: Boolean,
    onSelectDmHome: () -> Unit,
    onSelectGuild: (GuildId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeRailItem(isSelected = isDmHomeSelected, onClick = onSelectDmHome)
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.width(32.dp),
        )
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
private fun HomeRailItem(isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(40.dp)
            .height(40.dp)
            .background(bg, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("DM", style = MaterialTheme.typography.labelSmall, color = fg)
    }
}

@Composable
private fun DmListPane(
    dms: List<DmChannel>,
    selectedChannelId: ChannelId?,
    onSelectChannel: (ChannelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(spacing.space4)) {
        Text(
            "Direct Messages",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(spacing.space4))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(spacing.space4))
        if (dms.isEmpty()) {
            Text(
                "No direct messages yet",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(dms, key = { "dm-${it.id.value}" }) { dm ->
                    val recipient = dm.recipients.firstOrNull()
                    val label = recipient?.let { it.globalName ?: it.username } ?: "Unknown"
                    DmRow(
                        label = "@$label",
                        avatar = recipient,
                        isSelected = dm.id == selectedChannelId,
                        onClick = { onSelectChannel(dm.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DmRow(
    label: String,
    avatar: dev.puklic.domain.UserSummary?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (avatar != null) PuklicAvatar(user = avatar, size = 24.dp)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChannelListPane(
    channels: List<Channel>,
    selectedChannelId: ChannelId?,
    onSelectChannel: (ChannelId) -> Unit,
    onJoinVoiceChannel: (GuildId, ChannelId) -> Unit,
    voiceMembersByChannel: Map<ChannelId, List<VoiceMember>> = emptyMap(),
    resolveDisplayName: suspend (UserId) -> String = { it.value.toString() },
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Column(modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(spacing.space4)) {
        Text(
            "Channels",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
            // Phase 1 visible types: GUILD_TEXT (0) and GUILD_ANNOUNCEMENT (5) — both modelled as
            // GuildTextChannel by the protocol mapper. GUILD_CATEGORY (4) renders as a header,
            // never as a clickable row. Voice / stage / forum / thread types are filtered out.
            val textChannels = channels
                .filterIsInstance<GuildTextChannel>()
                .sortedBy { it.position }
            val voiceChannels = channels
                .filterIsInstance<GuildVoiceChannel>()
                .sortedBy { it.position }
            val categories = channels
                .filterIsInstance<GuildCategoryChannel>()
                .sortedBy { it.position }
            val categoryIds = categories.map { it.id }.toSet()
            // Channels with null parentId OR with parentId pointing to a category that wasn't
            // delivered by the gateway (e.g. permission-gated) are rendered as top-level so they
            // never silently disappear. This is the regression fix for #1: previously the strict
            // `parentId == cat.id` filter dropped every channel whose parent category was not in
            // the visible list.
            val orphanText = textChannels.filter { it.parentId == null || it.parentId !in categoryIds }
            val orphanVoice = voiceChannels.filter { it.parentId == null || it.parentId !in categoryIds }
            Logger.i("ChannelListPane") {
                "incoming channels: ${channels.size}, " +
                    "types: ${channels.groupBy { it::class.simpleName }.mapValues { it.value.size }}"
            }
            Logger.i("ChannelListPane") {
                "categories: ${categories.size}, text: ${textChannels.size}, " +
                    "withParent: ${textChannels.count { it.parentId != null }}, " +
                    "orphanRendered: ${orphanText.size}"
            }
            LazyColumn {
                if (orphanText.isNotEmpty()) {
                    items(orphanText, key = { "txt-${it.id.value}" }) { ch ->
                        ChannelListItem(
                            channel = ch,
                            isSelected = ch.id == selectedChannelId,
                            onClick = { onSelectChannel(ch.id) },
                        )
                    }
                }
                if (orphanVoice.isNotEmpty()) {
                    items(orphanVoice, key = { "voi-${it.id.value}" }) { ch ->
                        VoiceChannelEntry(
                            channel = ch,
                            members = voiceMembersByChannel[ch.id].orEmpty(),
                            resolveDisplayName = resolveDisplayName,
                            onJoin = { onJoinVoiceChannel(ch.guildId, ch.id) },
                        )
                    }
                }
                categories.forEach { cat ->
                    item(key = "cat-${cat.id.value}") {
                        CategoryHeader(
                            label = cat.name.orEmpty(),
                            isExpanded = true,
                            onToggle = {},
                        )
                    }
                    val underText = textChannels
                        .filter { it.parentId == cat.id }
                        .sortedBy { it.position }
                    items(underText, key = { "txt-${it.id.value}" }) { ch ->
                        ChannelListItem(
                            channel = ch,
                            isSelected = ch.id == selectedChannelId,
                            onClick = { onSelectChannel(ch.id) },
                        )
                    }
                    val underVoice = voiceChannels
                        .filter { it.parentId == cat.id }
                        .sortedBy { it.position }
                    items(underVoice, key = { "voi-${it.id.value}" }) { ch ->
                        VoiceChannelEntry(
                            channel = ch,
                            members = voiceMembersByChannel[ch.id].orEmpty(),
                            resolveDisplayName = resolveDisplayName,
                            onJoin = { onJoinVoiceChannel(ch.guildId, ch.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagePane(
    selectedChannelId: ChannelId?,
    selectedChannelName: String?,
    selectedChannelTopic: String?,
    selectedChannel: Channel?,
    messageOrchestrator: MessageOrchestrator?,
    platformOpen: PlatformOpen?,
    onChannelMentionClick: ((ChannelId) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        val nonTextEmptyState = selectedChannel?.let { nonTextChannelEmptyState(it.type) }
        when {
            selectedChannelId == null -> EmptyState(
                title = "Select a channel to start chatting",
                body = "Channels appear here once you join a server.",
            )
            messageOrchestrator == null -> EmptyState(
                title = "No active session",
                body = "Sign in to start receiving messages.",
            )
            nonTextEmptyState != null -> EmptyState(
                title = nonTextEmptyState.first,
                body = nonTextEmptyState.second,
            )
            else -> ChannelMessages(
                channelId = selectedChannelId,
                channelName = selectedChannelName,
                channelTopic = selectedChannelTopic,
                orchestrator = messageOrchestrator,
                platformOpen = platformOpen,
                uiScope = scope,
                onChannelMentionClick = onChannelMentionClick,
            )
        }
    }
}

/**
 * Returns (title, body) for non-text channel types so MessagePane can render an EmptyState
 * instead of attempting a REST `loadInitial` that would fail or return nothing. Returns null
 * for text-capable channel types (GUILD_TEXT, GUILD_ANNOUNCEMENT, DM, GROUP_DM, threads, forum/
 * media post channels).
 */
private fun nonTextChannelEmptyState(type: dev.puklic.domain.ChannelType): Pair<String, String>? =
    when (type) {
        dev.puklic.domain.ChannelType.GUILD_VOICE ->
            "Voice channel" to
                "Voice channels aren't supported yet. (Phase 3 roadmap covers voice.)"
        dev.puklic.domain.ChannelType.GUILD_STAGE_VOICE ->
            "Stage channel" to
                "Stage channels aren't supported yet. (Phase 3 roadmap covers voice.)"
        dev.puklic.domain.ChannelType.GUILD_DIRECTORY ->
            "Server directory" to
                "Server directories aren't supported yet."
        dev.puklic.domain.ChannelType.GUILD_CATEGORY ->
            "Category" to
                "Categories group channels and don't contain messages."
        else -> null
    }

@Composable
private fun ChannelMessages(
    channelId: ChannelId,
    channelName: String?,
    channelTopic: String?,
    orchestrator: MessageOrchestrator,
    platformOpen: PlatformOpen?,
    uiScope: CoroutineScope,
    onChannelMentionClick: ((ChannelId) -> Unit)? = null,
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

    val displayName = channelName?.takeIf { it.isNotBlank() } ?: channelId.value.toString()
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "#$displayName",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!channelTopic.isNullOrBlank()) {
                Text(
                    text = channelTopic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            MessageList(
                state = state,
                onLoadOlder = viewModel.viewModel::loadOlder,
                onMessageAction = {},
                modifier = Modifier.fillMaxWidth(),
                onReact = { message, emoji ->
                    val alreadyReacted = message.reactions.any { it.me && it.emoji == emoji }
                    viewModel.viewModel.toggleReaction(message.id, emoji, alreadyReacted)
                },
                onAttachmentClick = { att ->
                    val opener = platformOpen ?: return@MessageList
                    uiScope.launch { opener.openUrl(att.url) }
                },
                onChannelMentionClick = onChannelMentionClick,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Composer(
            draft = draft,
            placeholder = "Message #$displayName",
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

/**
 * A voice channel row plus its (possibly empty) list of current occupants. Members render
 * indented under the row so the visual grouping is unambiguous.
 */
@Composable
private fun VoiceChannelEntry(
    channel: GuildVoiceChannel,
    members: List<VoiceMember>,
    resolveDisplayName: suspend (UserId) -> String,
    onJoin: () -> Unit,
) {
    Column {
        ChannelListItem(
            channel = channel,
            isSelected = false,
            onClick = onJoin,
        )
        if (members.isNotEmpty()) {
            members
                .sortedBy { it.userId.value }
                .forEach { member ->
                    VoiceMemberRow(
                        member = member,
                        resolveDisplayName = resolveDisplayName,
                    )
                }
        }
    }
}

private class ChannelMessagesHolder(
    val viewModel: MessageListViewModel,
    val lifecycle: LifecycleRegistry,
)

/**
 * Voice dock at the bottom of the channel-list pane. JVM-only actual renders the real
 * voice status bar + settings dialog. Android/iOS actuals are stubs until those platforms
 * grow native audio backends (Voice Phase 3.1+).
 *
 * Per architect report `docs/03_infrastructure/architect-reports/2026-05-23-voice.md` §12.
 */
@Composable
internal expect fun VoiceDock(viewModel: MainViewModel)

