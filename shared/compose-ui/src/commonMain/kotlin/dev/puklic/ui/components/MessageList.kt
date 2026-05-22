package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.puklic.domain.ChatMessage
import dev.puklic.ids.EmojiId
import dev.puklic.ui.screens.main.MessageListState
import dev.puklic.ui.theme.LocalPuklicSpacing

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
                state.message,
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error,
            )
            is MessageListState.Loaded -> LazyColumn(reverseLayout = false) {
                if (state.isLoadingOlder) {
                    item { CircularProgressIndicator() }
                }
                items(state.messages, key = { it.id.value }) { message ->
                    MessageRow(message = message)
                }
            }
        }
    }
}
