package dev.puklic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.Attachment
import dev.puklic.domain.ChatMessage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MessageEmbed
import dev.puklic.domain.MessageType
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

/**
 * Single message row. Renders avatar + header + body + attachments + embeds.
 */
@Composable
public fun MessageRow(
    message: ChatMessage,
    groupedWithPrevious: Boolean = false,
    deliveryState: MessageDeliveryState = MessageDeliveryState.Committed,
    isMentionedUser: Boolean = false,
    isOwnMessage: Boolean = false,
    onReact: (EmojiRef) -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    @Suppress("UnusedParameter") onAuthorClick: (UserSummary) -> Unit = {},
    @Suppress("UnusedParameter") onAttachmentClick: (Attachment) -> Unit = {},
    onChannelMentionClick: ((dev.puklic.ids.ChannelId) -> Unit)? = null,
    onUserMentionClick: ((dev.puklic.ids.UserId) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val mentionBg = LocalPuklicColors.current.mentionBackground
    val background = if (isMentionedUser) mentionBg else MaterialTheme.colorScheme.background

    val systemLabel = systemMessageLabel(message)
    if (systemLabel != null) {
        SystemMessageRow(text = systemLabel, modifier = modifier)
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val onCopyText: () -> Unit = {
        val text = message.rawContent.ifBlank {
            message.parsedContent.blocks.joinToString("\n") { it.toString() }
        }
        if (text.isNotEmpty()) clipboard.setText(AnnotatedString(text))
    }
    val gestureModifier = Modifier
        .pointerInput(message.id.value) {
            detectTapGestures(onLongPress = { menuOpen = true })
        }
        .pointerInput(message.id.value) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.buttons.isSecondaryPressed) {
                        event.changes.forEach { it.consume() }
                        menuOpen = true
                    }
                }
            }
        }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .then(gestureModifier)
            .padding(vertical = spacing.space2, horizontal = spacing.space4),
    ) {
        MessageContextMenu(
            isOpen = menuOpen,
            onDismiss = { menuOpen = false },
            isOwnMessage = isOwnMessage,
            onCopyText = { onCopyText(); menuOpen = false },
            onCopyLink = { onCopyLink(); menuOpen = false },
            onEdit = { onEdit(); menuOpen = false },
            onDelete = { onDelete(); menuOpen = false },
        )
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
                    if (message.editedTimestamp != null) {
                        Spacer(Modifier.width(spacing.space2))
                        Text(
                            text = "(edited)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    onChannelClick = onChannelMentionClick,
                    onUserClick = onUserMentionClick,
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
            AttachmentRenderer(attachment = att, onOpen = { onAttachmentClick(att) })
        }
    }
}

@Composable
private fun EmbedList(embeds: List<MessageEmbed>) {
    val spacing = LocalPuklicSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        embeds.forEach { embed -> EmbedCard(embed = embed) }
    }
}

private const val EmbedDescriptionMaxLines = 4
private val EmbedThumbnailSize = 80.dp
private val EmbedImageMaxWidth = 400.dp
private val EmbedImageMaxHeight = 300.dp
private val EmbedAuthorIconSize = 24.dp
private val EmbedFooterIconSize = 16.dp

@Composable
private fun EmbedCard(embed: MessageEmbed) {
    val spacing = LocalPuklicSpacing.current
    val accent = embed.color?.let { Color(0xFF000000.toInt() or (it and 0xFFFFFF)) }
        ?: MaterialTheme.colorScheme.primary
    val uri = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))

        Column(
            modifier = Modifier.weight(1f).padding(spacing.space3),
            verticalArrangement = Arrangement.spacedBy(spacing.space2),
        ) {
            // Provider OR site (provider preferred — Discord sets it for OG link embeds).
            val siteName = embed.provider?.name?.takeIf { it.isNotBlank() }
            siteName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = embed.provider?.url?.let { Modifier.clickable { uri.openUri(it) } }
                        ?: Modifier,
                )
            }

            // Author row: avatar + name (clickable if url).
            embed.author?.let { author ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    author.iconUrl?.let { icon ->
                        AsyncImage(
                            model = icon,
                            contentDescription = null,
                            modifier = Modifier.size(EmbedAuthorIconSize).clip(CircleShape),
                        )
                    }
                    Text(
                        text = author.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = author.url?.let { Modifier.clickable { uri.openUri(it) } } ?: Modifier,
                    )
                }
            }

            // Title + thumbnail row.
            val titleText = embed.title?.takeIf { it.isNotBlank() }
            val thumbnail = embed.thumbnail
            if (titleText != null || thumbnail != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.space3),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(spacing.space2),
                    ) {
                        titleText?.let { title ->
                            val clickable = embed.url?.let { url ->
                                Modifier.clickable { uri.openUri(url) }
                            } ?: Modifier
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = if (embed.url != null) TextDecoration.Underline else null,
                                ),
                                color = if (embed.url != null) linkColor else MaterialTheme.colorScheme.onSurface,
                                modifier = clickable,
                            )
                        }
                        embed.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = EmbedDescriptionMaxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    thumbnail?.let { thumb ->
                        AsyncImage(
                            model = thumb.proxyUrl ?: thumb.url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(EmbedThumbnailSize)
                                .clip(RoundedCornerShape(4.dp))
                                .let { m ->
                                    val openUrl = embed.url
                            if (openUrl != null) m.clickable { uri.openUri(openUrl) } else m
                                },
                        )
                    }
                }
            } else {
                // No title — still render description if any.
                embed.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = EmbedDescriptionMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Fields: two-column grid for consecutive inline runs, single-col otherwise.
            if (embed.fields.isNotEmpty()) {
                EmbedFields(embed.fields)
            }

            // Full image — wider than thumbnail, bounded.
            embed.image?.let { img ->
                AsyncImage(
                    model = img.proxyUrl ?: img.url,
                    contentDescription = embed.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = EmbedImageMaxWidth)
                        .heightIn(max = EmbedImageMaxHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .let { m ->
                            val openUrl = embed.url
                            if (openUrl != null) m.clickable { uri.openUri(openUrl) } else m
                        },
                )
            }

            // Video without inline player — show "open in browser" cue via title click; nothing extra here.

            // Footer: icon + text.
            embed.footer?.let { footer ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.space2),
                ) {
                    footer.iconUrl?.let { icon ->
                        AsyncImage(
                            model = icon,
                            contentDescription = null,
                            modifier = Modifier.size(EmbedFooterIconSize).clip(CircleShape),
                        )
                    }
                    Text(
                        text = footer.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmbedFields(fields: List<dev.puklic.domain.EmbedField>) {
    val spacing = LocalPuklicSpacing.current
    // Group consecutive inline=true fields into rows of up to 2 (Discord uses 3 cols at full width,
    // but we run at 80% width and reserve room for thumbnail — 2 cols reads cleaner).
    val rows = mutableListOf<List<dev.puklic.domain.EmbedField>>()
    var i = 0
    while (i < fields.size) {
        val f = fields[i]
        if (f.inline) {
            val group = mutableListOf(f)
            var j = i + 1
            while (j < fields.size && fields[j].inline && group.size < 2) {
                group += fields[j]; j++
            }
            rows += group
            i = j
        } else {
            rows += listOf(f)
            i++
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.space2)) {
        rows.forEach { row ->
            if (row.size == 1) {
                EmbedFieldCell(row[0], Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.space3)) {
                    row.forEach { f ->
                        EmbedFieldCell(f, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Right-click / long-press context menu for a chat message. Items:
 *  - Copy text (always)
 *  - Copy message link (always)
 *  - Edit (own messages only)
 *  - Delete (own messages only)
 *
 * Items are computed from [isOwnMessage] so the menu adapts to the viewer's authorship without
 * the caller having to pre-filter callbacks. Closing happens via the parent (each item resets
 * `menuOpen` before invoking the callback).
 */
@Composable
internal fun MessageContextMenu(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    isOwnMessage: Boolean,
    onCopyText: () -> Unit,
    onCopyLink: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = isOpen, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Copy text") }, onClick = onCopyText)
        DropdownMenuItem(text = { Text("Copy message link") }, onClick = onCopyLink)
        if (isOwnMessage) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = onEdit)
            DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
        }
    }
}

/**
 * Render a single-line "system" event row (call started, recipient added, channel renamed, …).
 * Discord uses a muted, italic style with no avatar; we mirror that. Pure presentation.
 */
@Composable
internal fun SystemMessageRow(text: String, modifier: Modifier = Modifier) {
    val spacing = LocalPuklicSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.space2, horizontal = spacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Maps a non-DEFAULT [MessageType] to a user-facing system line. Returns null for
 * [MessageType.DEFAULT] / [MessageType.REPLY] / [MessageType.UNKNOWN] so the regular bubble
 * renders. Pure function — no Compose state — so it is also reachable from unit tests.
 */
internal fun systemMessageLabel(message: ChatMessage): String? {
    val name = message.author.globalName ?: message.author.username
    return when (message.type) {
        MessageType.CALL -> "$name started a call."
        MessageType.RECIPIENT_ADD -> "$name added someone to the group."
        MessageType.RECIPIENT_REMOVE -> "$name removed someone from the group."
        MessageType.CHANNEL_NAME_CHANGE -> "$name changed the channel name."
        MessageType.CHANNEL_ICON_CHANGE -> "$name changed the channel icon."
        MessageType.CHANNEL_PINNED_MESSAGE -> "$name pinned a message to this channel."
        MessageType.USER_JOIN -> "$name joined."
        MessageType.DEFAULT, MessageType.REPLY, MessageType.UNKNOWN -> null
    }
}

@Composable
private fun EmbedFieldCell(field: dev.puklic.domain.EmbedField, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            field.name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            field.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
