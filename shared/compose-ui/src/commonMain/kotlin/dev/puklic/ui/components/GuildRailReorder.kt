package dev.puklic.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import dev.puklic.ids.GuildId

private const val GUILD_KEY_PREFIX = "rail-guild-"
private const val EDGE_SCROLL_ZONE_FRACTION = 0.15f
private const val EDGE_SCROLL_STEP_PX = 14f

internal fun guildRailKey(id: GuildId): String = "$GUILD_KEY_PREFIX${id.value}"

/**
 * Drag-and-drop state of the guild rail. While a drag is active [order] is the working order shown
 * by the rail; on drop it is handed to [onDropped] once, and kept as [pendingOrder] until the
 * upstream state catches up so the rail never flickers back to the old order.
 */
internal class GuildRailReorderState(
    private val listState: LazyListState,
    private val onDropped: (List<GuildId>) -> Unit,
) {
    var draggedId: GuildId? by mutableStateOf(null)
        private set
    var dragOffset: Float by mutableFloatStateOf(0f)
        private set
    var pendingOrder: List<GuildId>? by mutableStateOf(null)
        private set

    private var startOrder: List<GuildId> = emptyList()

    fun start(id: GuildId, current: List<GuildId>) {
        startOrder = current
        pendingOrder = current
        draggedId = id
        dragOffset = 0f
    }

    fun dragBy(deltaY: Float) {
        val dragged = draggedId ?: return
        dragOffset += deltaY
        val items = listState.layoutInfo.visibleItemsInfo
        val source = items.firstOrNull { it.key == guildRailKey(dragged) } ?: return
        val center = source.offset + source.size / 2f + dragOffset
        val target = items.firstOrNull { it.isGuildOtherThan(dragged) && center >= it.offset && center <= it.offset + it.size }
        if (target != null) swap(dragged, source, target)
        scrollNearEdge(center)
    }

    fun end() {
        val dropped = pendingOrder
        draggedId = null
        dragOffset = 0f
        if (dropped == null || dropped == startOrder) pendingOrder = null else onDropped(dropped)
    }

    fun upstreamChanged() {
        if (draggedId == null) pendingOrder = null
    }

    private fun swap(dragged: GuildId, source: LazyListItemInfo, target: LazyListItemInfo) {
        val order = pendingOrder?.toMutableList() ?: return
        val targetId = order.firstOrNull { guildRailKey(it) == target.key } ?: return
        val from = order.indexOf(dragged)
        val to = order.indexOf(targetId)
        order.add(to, order.removeAt(from))
        pendingOrder = order
        dragOffset -= (target.offset - source.offset)
    }

    private fun scrollNearEdge(center: Float) {
        val viewportStart = listState.layoutInfo.viewportStartOffset
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val zone = (viewportEnd - viewportStart) * EDGE_SCROLL_ZONE_FRACTION
        val step = when {
            center < viewportStart + zone -> -EDGE_SCROLL_STEP_PX
            center > viewportEnd - zone -> EDGE_SCROLL_STEP_PX
            else -> return
        }
        dragOffset += listState.dispatchRawDelta(step)
    }

    private fun LazyListItemInfo.isGuildOtherThan(id: GuildId): Boolean =
        (key as? String)?.startsWith(GUILD_KEY_PREFIX) == true && key != guildRailKey(id)
}

/**
 * Starts a guild drag: immediately on a mouse button press once the pointer moves past touch
 * slop, after a long press for touch and stylus input.
 */
internal fun Modifier.guildDragGesture(
    id: GuildId,
    currentOrder: () -> List<GuildId>,
    state: GuildRailReorderState,
): Modifier = pointerInput(id) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val grabbed = when (down.type) {
            PointerType.Mouse -> awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            else -> awaitLongPressOrCancellation(down.id)
        } ?: return@awaitEachGesture
        state.start(id, currentOrder())
        drag(grabbed.id) { change ->
            change.consume()
            state.dragBy(change.positionChange().y)
        }
        state.end()
    }
}
