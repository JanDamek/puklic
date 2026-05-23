package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.Attachment
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageEmbed
import dev.puklic.domain.UserSummary
import dev.puklic.ui.theme.LocalPuklicColors
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Delivery state per `docs/04_ui/component-library.md` §MessageRow. */
public sealed interface MessageDeliveryState {
    public data object Committed : MessageDeliveryState
    public data object Sending : MessageDeliveryState
    public data class Failed(val message: String) : MessageDeliveryState
}

private val AvatarSize = 32.dp
private const val KB: Long = 1024
private const val MB: Long = KB * 1024
private const val GB: Long = MB * 1024

/**
 * Single message row. Renders avatar + header + body + attachments + embeds.
 */
@Composable
public fun MessageRow(
    message: ChatMessage,
    groupedWithPrevious: Boolean = false,
    deliveryState: MessageDeliveryState = MessageDeliveryState.Committed,
    isMentionedUser: Boolean = false,
    onReact: (EmojiRef) -> Unit = {},
    @Suppress("UnusedParameter") onEdit: () -> Unit = {},
    @Suppress("UnusedParameter") onDelete: () -> Unit = {},
    @Suppress("UnusedParameter") onCopyLink: () -> Unit = {},
    @Suppress("UnusedParameter") onAuthorClick: (UserSummary) -> Unit = {},
    @Suppress("UnusedParameter") onAttachmentClick: (Attachment) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val mentionBg = LocalPuklicColors.current.mentionBackground
    val background = if (isMentionedUser) mentionBg else MaterialTheme.colorScheme.background

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = spacing.space2, horizontal = spacing.space4),
    ) {
        if (!groupedWithPrevious) {
            PuklicAvatar(user = message.author, size = AvatarSize)
            Spacer(Modifier.width(spacing.space4))
        } else {
            Spacer(Modifier.width(AvatarSize + spacing.space4))
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!groupedWithPrevious) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.author.globalName ?: message.author.username,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(spacing.space3))
                    Text(
                        text = TimestampFormat.header(message.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Body: prefer parsed rich text, but if parser produced nothing for non-blank input,
            // fall back to raw content so we never silently drop user-visible text.
            val hasParsedContent = message.parsedContent.blocks.isNotEmpty()
            if (hasParsedContent) {
                RichTextView(
                    document = message.parsedContent,
                    onLinkClick = {},
                    onMentionClick = {},
                    modifier = Modifier.padding(top = if (groupedWithPrevious) 0.dp else spacing.space1),
                )
            } else if (message.rawContent.isNotBlank()) {
                Text(
                    text = message.rawContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = if (groupedWithPrevious) 0.dp else spacing.space1),
                )
            }

            if (message.attachments.isNotEmpty()) {
                Spacer(Modifier.height(spacing.space2))
                AttachmentList(
                    attachments = message.attachments,
                    onAttachmentClick = onAttachmentClick,
                )
            }

            if (message.embeds.isNotEmpty()) {
                Spacer(Modifier.height(spacing.space2))
                EmbedList(embeds = message.embeds)
            }

            if (message.reactions.isNotEmpty()) {
                Spacer(Modifier.height(spacing.space2))
                var pickerOpen by remember { mutableStateOf(false) }
                var recent by remember { mutableStateOf<List<EmojiRef>>(emptyList()) }
                ReactionsRow(
                    reactions = message.reactions,
                    onToggle = onReact,
                    onPickerOpen = { pickerOpen = true },
                )
                if (pickerOpen) {
                    EmojiPickerDialog(
                        onDismiss = { pickerOpen = false },
                        onPick = { emoji ->
                            recent = (listOf(emoji) + recent).distinct().take(16)
                            onReact(emoji)
                            pickerOpen = false
                        },
                        recent = recent,
                    )
                }
            }

            if (deliveryState is MessageDeliveryState.Failed) {
                Text(
                    deliveryState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AttachmentList(
    attachments: List<Attachment>,
    onAttachmentClick: (Attachment) -> Unit,
) {
    val spacing = LocalPuklicSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        attachments.forEach { att ->
            AttachmentCard(attachment = att, onClick = { onAttachmentClick(att) })
        }
    }
}

@Composable
private fun AttachmentCard(attachment: Attachment, onClick: () -> Unit) {
    val spacing = LocalPuklicSpacing.current
    val contentType = attachment.contentType.orEmpty()
    val isImage = contentType.startsWith("image/")
    val isAudio = contentType.startsWith("audio/")
    val icon = when {
        isImage -> "IMG"
        isAudio -> "AUDIO"
        contentType.startsWith("video/") -> "VIDEO"
        else -> "FILE"
    }

    if (isImage) {
        AsyncImage(
            model = attachment.proxyUrl,
            contentDescription = attachment.description ?: attachment.filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
        )
        return
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(spacing.space3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            Text(text = icon, style = MaterialTheme.typography.titleLarge)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= GB -> "${"%.1f".formatLocale(bytes.toDouble() / GB)} GB"
    bytes >= MB -> "${"%.1f".formatLocale(bytes.toDouble() / MB)} MB"
    bytes >= KB -> "${"%.1f".formatLocale(bytes.toDouble() / KB)} KB"
    else -> "$bytes B"
}

private fun String.formatLocale(value: Double): String {
    // Minimal cross-platform 1-decimal formatter; avoids kotlin.text.format expect/actual.
    val scaled = (value * 10.0).toLong()
    val whole = scaled / 10
    val frac = scaled % 10
    return "$whole.$frac"
}

@Composable
private fun EmbedList(embeds: List<MessageEmbed>) {
    val spacing = LocalPuklicSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        embeds.forEach { embed -> EmbedCard(embed = embed) }
    }
}

@Composable
private fun EmbedCard(embed: MessageEmbed) {
    val spacing = LocalPuklicSpacing.current
    val accent = embed.color?.let { Color(0xFF000000.toInt() or (it and 0xFFFFFF)) }
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(
            modifier = Modifier.padding(spacing.space3),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            embed.author?.let { author ->
                Text(
                    text = author.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            embed.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            embed.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (embed.fields.isNotEmpty()) {
                embed.fields.forEach { field ->
                    Column {
                        Text(
                            field.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            field.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            embed.image?.let { img ->
                AsyncImage(
                    model = img.proxyUrl ?: img.url,
                    contentDescription = embed.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                )
            }
            embed.footer?.let { footer ->
                Text(
                    text = footer.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
