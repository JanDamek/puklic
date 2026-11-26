package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.puklic.domain.Guild
import dev.puklic.ids.GuildId
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.guild_rail_add_friend
import dev.puklic.ui.resources.guild_rail_direct_messages_label
import dev.puklic.ui.resources.guild_rail_join_server
import org.jetbrains.compose.resources.stringResource

private const val RAIL_ITEM_SIZE_DP = 40
private const val RAIL_DIVIDER_WIDTH_DP = 32

/**
 * Server rail: DM entry on top, a scrollable and drag-reorderable guild list in the middle and
 * the fixed "Add friend" / "Join server" actions below it. Guilds are reordered with a mouse drag
 * or a touch long press followed by a drag; the final order is reported once through
 * [onReorderGuilds] when the drag ends.
 */
@Composable
public fun GuildRail(
    guilds: List<Guild>,
    selectedGuildId: GuildId?,
    isDmHomeSelected: Boolean,
    guildHasMention: Map<GuildId, Boolean>,
    onSelectDmHome: () -> Unit,
    onSelectGuild: (GuildId) -> Unit,
    onOpenAddFriend: () -> Unit,
    onOpenJoinServer: () -> Unit,
    onReorderGuilds: (List<GuildId>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestOnReorder = rememberUpdatedState(onReorderGuilds)
    val reorder = remember(listState) { GuildRailReorderState(listState) { latestOnReorder.value(it) } }
    LaunchedEffect(guilds) { reorder.upstreamChanged() }
    val shown = guilds.inDisplayOrder(reorder.pendingOrder)
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeRailItem(isSelected = isDmHomeSelected, onClick = onSelectDmHome)
        RailDivider()
        LazyColumn(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            state = listState,
        ) {
            items(shown, key = { guildRailKey(it.id) }) { g ->
                val dragging = reorder.draggedId == g.id
                GuildRailItem(
                    guild = g,
                    isSelected = g.id == selectedGuildId,
                    hasUnread = guildHasMention[g.id] == true,
                    mentionCount = 0,
                    onClick = { onSelectGuild(g.id) },
                    modifier = Modifier
                        .guildDragGesture(g.id, { shown.map { it.id } }, reorder)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) reorder.dragOffset else 0f },
                )
            }
        }
        RailDivider()
        RailActionItem(Icons.Filled.PersonAdd, stringResource(Res.string.guild_rail_add_friend), onOpenAddFriend)
        RailActionItem(Icons.Filled.Public, stringResource(Res.string.guild_rail_join_server), onOpenJoinServer)
    }
}

private fun List<Guild>.inDisplayOrder(pending: List<GuildId>?): List<Guild> {
    if (pending == null) return this
    val byId = associateBy { it.id }
    val placed = pending.mapNotNull { byId[it] }
    val placedIds = placed.mapTo(mutableSetOf()) { it.id }
    return placed + filter { it.id !in placedIds }
}

@Composable
private fun RailDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.width(RAIL_DIVIDER_WIDTH_DP.dp),
    )
}

@Composable
private fun RailActionItem(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(RAIL_ITEM_SIZE_DP.dp)
            .height(RAIL_ITEM_SIZE_DP.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeRailItem(isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(RAIL_ITEM_SIZE_DP.dp)
            .height(RAIL_ITEM_SIZE_DP.dp)
            .background(bg, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(Res.string.guild_rail_direct_messages_label), style = MaterialTheme.typography.labelSmall, color = fg)
    }
}
