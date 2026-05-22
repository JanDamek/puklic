package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.UserSummary
import dev.puklic.domain.EmojiRef
import dev.puklic.ui.theme.LocalPuklicColors
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Delivery state per `docs/04_ui/component-library.md` §MessageRow. */
public sealed interface MessageDeliveryState {
    public data object Committed : MessageDeliveryState
    public data object Sending : MessageDeliveryState
    public data class Failed(val message: String) : MessageDeliveryState
}

/**
 * Single message row. Phase 1 renders avatar + header + flattened body. Hover-revealed
 * actions and reactions chips land in Phase 2.
 */
@Composable
public fun MessageRow(
    message: ChatMessage,
    groupedWithPrevious: Boolean = false,
    deliveryState: MessageDeliveryState = MessageDeliveryState.Committed,
    isMentionedUser: Boolean = false,
    @Suppress("UnusedParameter") onReact: (EmojiRef) -> Unit = {},
    @Suppress("UnusedParameter") onEdit: () -> Unit = {},
    @Suppress("UnusedParameter") onDelete: () -> Unit = {},
    @Suppress("UnusedParameter") onCopyLink: () -> Unit = {},
    @Suppress("UnusedParameter") onAuthorClick: (UserSummary) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val mentionBg = LocalPuklicColors.current.mentionBackground
    val background = if (isMentionedUser) mentionBg else MaterialTheme.colorScheme.background
    val opacity = if (deliveryState is MessageDeliveryState.Sending) 0.6f else 1f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = spacing.space2, horizontal = spacing.space4),
    ) {
        if (!groupedWithPrevious) {
            PuklicAvatar(user = message.author, size = 32.dp)
            Spacer(Modifier.width(spacing.space4))
        } else {
            Spacer(Modifier.width(32.dp + spacing.space4))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!groupedWithPrevious) {
                Row {
                    Text(
                        text = message.author.globalName ?: message.author.username,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.width(spacing.space3))
                    Text(
                        text = message.timestamp.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RichTextView(
                document = message.parsedContent,
                onLinkClick = {},
                onMentionClick = {},
                modifier = Modifier.padding(top = if (groupedWithPrevious) 0.dp else spacing.space1),
            )
            if (deliveryState is MessageDeliveryState.Failed) {
                Text(
                    deliveryState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") opacity
}
