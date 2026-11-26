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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.domain.Attachment
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.ids.EmojiId
import dev.puklic.ids.UserId
import dev.puklic.ui.screens.main.MessageListState
import dev.puklic.ui.theme.LocalPuklicSpacing
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import dev.puklic.ui.resources.Res
import dev.puklic.ui.resources.message_list_empty
import dev.puklic.ui.resources.message_list_error_failed
import dev.puklic.ui.resources.message_list_error_forbidden
import dev.puklic.ui.resources.message_list_error_generic
import dev.puklic.ui.resources.message_list_error_network
import dev.puklic.ui.resources.message_list_error_not_found
import dev.puklic.ui.resources.message_list_error_rate_limited
import dev.puklic.ui.resources.message_list_error_timed_out
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val GROUPING_WINDOW_SECONDS: Long = 300L // 5 minutes per docs/04_ui/screens.md MessagePane

// Trigger an older-page fetch once the user scrolls within this many items of the oldest (top) end.
private const val LOAD_OLDER_THRESHOLD: Int = 5

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
    onLoadOlder: () -> Unit,
    onMessageAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
    selfUserId: UserId? = null,
    onReact: (ChatMessage, EmojiRef) -> Unit = { _, _ -> },
    onAttachmentClick: (Attachment) -> Unit = {},
    onChannelMentionClick: ((dev.puklic.ids.ChannelId) -> Unit)? = null,
    onUserMentionClick: ((dev.puklic.ids.UserId) -> Unit)? = null,
    firstUnreadId: dev.puklic.ids.MessageId? = null,
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
                stringResource(Res.string.message_list_empty),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is MessageListState.Error -> Text(
                friendlyErrorMessage(state),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
            is MessageListState.Loaded -> if (state.messages.isEmpty()) {
                Text(
                    stringResource(Res.string.message_list_empty),
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
                // With reverseLayout=true and newest-first list, the oldest message sits at the
                // largest index. Scrolling up toward older messages pushes the last visible index
                // toward totalItemsCount-1; fire onLoadOlder once we're within the threshold.
                val shouldLoadOlder by remember(listState) {
                    derivedStateOf {
                        val lastVisible =
                            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val total = listState.layoutInfo.totalItemsCount
                        total > 0 && lastVisible >= total - LOAD_OLDER_THRESHOLD
                    }
                }
                LaunchedEffect(shouldLoadOlder) {
                    if (shouldLoadOlder) onLoadOlder()
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
                        val msg = msgs[idx]
                        val zone = TimeZone.currentSystemDefault()
                        val newDay = isNewDay(
                            current = msg.timestamp,
                            previousOlder = prev?.timestamp,
                            zone = zone,
                        )
                        val isFirstUnread = firstUnreadId != null && msg.id == firstUnreadId
                        Column {
                            if (newDay) {
                                DateSeparator(longDateLabel(msg.timestamp.toLocalDateTime(zone).date))
                            }
                            if (isFirstUnread) {
                                UnreadDivider()
                            }
                            MessageRow(
                                message = msg,
                                groupedWithPrevious = grouped,
                                isOwnMessage = selfUserId != null && msg.author.id == selfUserId,
                                onReact = { emoji -> onReact(msg, emoji) },
                                onAttachmentClick = onAttachmentClick,
                                onChannelMentionClick = onChannelMentionClick,
                                onUserMentionClick = onUserMentionClick,
                                onDelete = { onMessageAction(MessageAction.Delete(msg)) },
                                onEdit = { onMessageAction(MessageAction.Edit(msg, msg.rawContent)) },
                                onCopyLink = { onMessageAction(MessageAction.CopyLink(msg)) },
                            )
                        }
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
 * Maps a failed load (REST status text, Discord JSON error bodies, network exceptions) to a short
 * localized user-facing line. Avoids leaking JSON payloads or stack traces into the UI.
 */
@Composable
internal fun friendlyErrorMessage(error: MessageListState.Error): String {
    val raw = when {
        error.timedOut -> stringResource(Res.string.message_list_error_timed_out)
        else -> error.message ?: stringResource(Res.string.message_list_error_generic)
    }
    return stringResource(raw.toErrorResource(), *raw.errorArgs())
}

private fun String.toErrorResource(): StringResource = when {
    contains("50001") ||
        contains("Forbidden", ignoreCase = true) ||
        contains("Missing Access", ignoreCase = true) -> Res.string.message_list_error_forbidden
    contains("404") || contains("Not Found", ignoreCase = true) -> Res.string.message_list_error_not_found
    contains("429") || contains("rate limit", ignoreCase = true) -> Res.string.message_list_error_rate_limited
    contains("Network", ignoreCase = true) ||
        contains("Cannot reach", ignoreCase = true) ||
        contains("UnknownHost", ignoreCase = true) ||
        contains("timeout", ignoreCase = true) -> Res.string.message_list_error_network
    else -> Res.string.message_list_error_failed
}

private fun String.errorArgs(): Array<String> = arrayOf(take(MAX_RAW_ERROR_LENGTH))

private const val MAX_RAW_ERROR_LENGTH = 120
