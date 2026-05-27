package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import dev.puklic.ui.screens.main.PendingAttachmentItem
import dev.puklic.ui.screens.main.PendingAttachmentStatus
import dev.puklic.ui.theme.LocalPuklicSpacing

/** Format actions per `docs/04_ui/component-library.md` §Composer. */
public enum class FormatCommand { BOLD, ITALIC, STRIKETHROUGH, INLINE_CODE, CODE_BLOCK, LINK, SPOILER }

/** Outcome of a key event reaching the composer's text field. */
internal enum class ComposerAction { Send, InsertNewline, Blur, Ignored }

/**
 * Pure decision logic for composer key handling. Extracted for unit testing without
 * a Compose UI runtime. See `docs/03_infrastructure/architect-reports/2026-05-24-send-messages.md` §3.
 */
internal fun composerKeyAction(
    keyDown: Boolean,
    isEnter: Boolean,
    isEscape: Boolean,
    isShift: Boolean,
): ComposerAction = when {
    !keyDown -> ComposerAction.Ignored
    isEscape -> ComposerAction.Blur
    isEnter && isShift -> ComposerAction.InsertNewline
    isEnter -> ComposerAction.Send
    else -> ComposerAction.Ignored
}

/**
 * Pure decision logic for whether the Send button should be enabled. Extracted so this is
 * unit-testable without Compose runtime. Per architect comment on issue #23: empty text plus at
 * least one attachment is a valid send; sending is disabled while uploads are in flight.
 */
internal fun composerCanSend(
    draftBlank: Boolean,
    attachmentCount: Int,
    anyUploading: Boolean,
    enabled: Boolean,
): Boolean {
    if (!enabled || anyUploading) return false
    return !draftBlank || attachmentCount > 0
}

private fun Modifier.onComposerKeyEvent(
    onSend: () -> Unit,
    onBlur: () -> Unit,
): Modifier = onPreviewKeyEvent { e ->
    val action = composerKeyAction(
        keyDown = e.type == KeyEventType.KeyDown,
        isEnter = e.key == Key.Enter || e.key == Key.NumPadEnter,
        isEscape = e.key == Key.Escape,
        isShift = e.isShiftPressed,
    )
    when (action) {
        ComposerAction.Send -> { onSend(); true }
        ComposerAction.Blur -> { onBlur(); true }
        ComposerAction.InsertNewline, ComposerAction.Ignored -> false
    }
}

@Composable
public fun Composer(
    draft: String,
    placeholder: String,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    attachments: List<PendingAttachmentItem> = emptyList(),
    onAttachClick: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    @Suppress("UnusedParameter") onFormat: (FormatCommand) -> Unit = {},
    @Suppress("UnusedParameter") onEmojiClick: () -> Unit = {},
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalPuklicSpacing.current
    val anyUploading = attachments.any { it.status == PendingAttachmentStatus.UPLOADING }
    val canSend = composerCanSend(
        draftBlank = draft.isBlank(),
        attachmentCount = attachments.size,
        anyUploading = anyUploading,
        enabled = isEnabled,
    )
    val attemptSend = { if (canSend) onSubmit() }
    val focus = LocalFocusManager.current
    Column(modifier = modifier.fillMaxWidth().padding(spacing.space4)) {
        if (attachments.isNotEmpty()) {
            AttachmentChipsRow(
                items = attachments,
                onRemove = onRemoveAttachment,
            )
            Spacer(Modifier.height(spacing.space2))
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onAttachClick, enabled = isEnabled) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "Attach file")
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(placeholder) },
                enabled = isEnabled,
                modifier = Modifier
                    .weight(1f)
                    .onComposerKeyEvent(
                        onSend = attemptSend,
                        onBlur = { focus.clearFocus() },
                    ),
            )
            IconButton(onClick = attemptSend, enabled = canSend) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun AttachmentChipsRow(
    items: List<PendingAttachmentItem>,
    onRemove: (String) -> Unit,
) {
    val spacing = LocalPuklicSpacing.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.space2),
    ) {
        items(items, key = { it.attachment.id }) { item ->
            AssistChip(
                onClick = { onRemove(item.attachment.id) },
                label = {
                    val sizeKb = item.attachment.sizeBytes / BYTES_PER_KIB
                    Text("${item.attachment.filename} (${sizeKb} KB)")
                },
                trailingIcon = {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove ${item.attachment.filename}",
                        modifier = Modifier.padding(start = spacing.space1),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

private const val BYTES_PER_KIB: Long = 1024L
