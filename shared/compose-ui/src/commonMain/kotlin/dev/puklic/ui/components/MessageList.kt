package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.domain.ChatMessage
import dev.puklic.ids.EmojiId
import dev.puklic.ui.screens.main.MessageListState
import dev.puklic.ui.theme.LocalPuklicSpacing

private const val GROUPING_WINDOW_SECONDS: Long = 300L // 5 minutes per docs/04_ui/screens.md MessagePane

/** Aggregated action surface for [MessageList]. */
public sealed interface MessageAction {
    public data class Edit(val message: ChatMessage, val newContent: String) : MessageAction
    public data class Delete(val message: ChatMessage) : MessageAction
    public data class React(val message: ChatMessage, val emojiId: EmojiId) : MessageAction
    public data class CopyLink(val message: ChatMessage) : MessageAction
}

@Composable
public fun MessageList(
    state: MessageListState,
    @Suppress("UnusedParameter") onLoadOlder: () -> Unit,
    @Suppress("UnusedParameter") onMessageAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            MessageListState.Loading -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                repeat(5) { MessageRowSkeleton() }
            }
            MessageListState.Empty -> Text(
                "It's quiet here. Start the conversation.",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is MessageListState.Error -> Text(
                friendlyErrorMessage(state.message),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
            is MessageListState.Loaded -> if (state.messages.isEmpty()) {
                Text(
                    "It's quiet here. Start the conversation.",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Repository delivers ascending (oldest -> newest). For Discord-style
                // bottom-anchored rendering we reverse to newest-first and use
                // reverseLayout=true so msgs[0] (newest) sits at the visual bottom.
                val msgs = remember(state.messages) { state.messages.asReversed() }
                val listState = rememberLazyListState()
                // Auto-scroll to bottom (newest) when a new message arrives if the user
                // is already near the bottom; preserve position if they scrolled up.
                LaunchedEffect(msgs.firstOrNull()?.id?.value) {
                    if (msgs.isNotEmpty() && listState.firstVisibleItemIndex <= 1) {
                        listState.scrollToItem(0)
                    }
                }
                LazyColumn(state = listState, reverseLayout = true) {
                    items(msgs.size, key = { idx -> msgs[idx].id.value }) { idx ->
                        // With reverseLayout=true and newest-first list, the message
                        // visually above this one is the older sibling at idx+1.
                        val prev = msgs.getOrNull(idx + 1)
                        val grouped = prev != null &&
                            prev.author.id == msgs[idx].author.id &&
                            kotlin.math.abs(
                                msgs[idx].timestamp.epochSeconds - prev.timestamp.epochSeconds,
                            ) <= GROUPING_WINDOW_SECONDS
                        MessageRow(message = msgs[idx], groupedWithPrevious = grouped)
                    }
                    if (state.isLoadingOlder) {
                        item { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

/**
 * Maps raw error strings (REST status text, Discord JSON error bodies, network exceptions) to a
 * short user-facing line. Avoids leaking JSON payloads or stack traces into the UI. English only;
 * localization is part of Phase 2 L10n work.
 */
internal fun friendlyErrorMessage(raw: String): String = when {
    raw.contains("50001") ||
        raw.contains("Forbidden", ignoreCase = true) ||
        raw.contains("Missing Access", ignoreCase = true) ->
        "You don't have permission to view this channel."
    raw.contains("404") || raw.contains("Not Found", ignoreCase = true) ->
        "Channel not found."
    raw.contains("429") || raw.contains("rate limit", ignoreCase = true) ->
        "Rate limited by Discord. Try again in a moment."
    raw.contains("Network", ignoreCase = true) ||
        raw.contains("Cannot reach", ignoreCase = true) ||
        raw.contains("UnknownHost", ignoreCase = true) ||
        raw.contains("timeout", ignoreCase = true) ->
        "Cannot reach Discord. Check your connection."
    else -> "Failed to load messages: ${raw.take(120)}"
}
