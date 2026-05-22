package dev.puklic.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.puklic.domain.MentionTarget
import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.RichTextInline

/**
 * Renders a [RichTextDocument] per `docs/02_domain/richtext-ast.md`.
 *
 * Phase 1 implementation is a minimal flattener — produces a single AnnotatedString-equivalent
 * plain-text rendering. Full recursive rendering with mention chips, code blocks, etc. lands
 * in step 15-16 once resolvers are wired.
 */
@Composable
public fun RichTextView(
    document: RichTextDocument,
    @Suppress("UnusedParameter") onLinkClick: (String) -> Unit,
    @Suppress("UnusedParameter") onMentionClick: (MentionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        document.blocks.forEach { block ->
            Text(
                text = flatten(block),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun flatten(block: RichTextBlock): String = when (block) {
    is RichTextBlock.Paragraph -> block.runs.joinToString(separator = "") { flatten(it) }
    is RichTextBlock.CodeBlock -> block.content
    is RichTextBlock.Quote -> block.content.joinToString(separator = "\n") { flatten(it) }
    is RichTextBlock.Heading -> block.runs.joinToString(separator = "") { flatten(it) }
    is RichTextBlock.List -> block.items.joinToString(separator = "\n") { item ->
        item.content.joinToString(separator = "\n") { flatten(it) }
    }
    is RichTextBlock.ListItem -> block.content.joinToString(separator = "\n") { flatten(it) }
}

private fun flatten(inline: RichTextInline): String = when (inline) {
    is RichTextInline.Text -> inline.content
    is RichTextInline.InlineCode -> inline.content
    is RichTextInline.Link -> inline.display.joinToString(separator = "") { flatten(it) }
    is RichTextInline.Mention -> when (val target = inline.target) {
        is MentionTarget.User -> "@${target.id.value}"
        is MentionTarget.Role -> "@&${target.id.value}"
        is MentionTarget.Channel -> "#${target.id.value}"
        MentionTarget.Everyone -> "@everyone"
        MentionTarget.Here -> "@here"
    }
    is RichTextInline.Emoji -> ":emoji:"
    is RichTextInline.Spoiler -> inline.content.joinToString(separator = "") { flatten(it) }
    is RichTextInline.Timestamp -> inline.instant.toString()
    is RichTextInline.LineBreak -> "\n"
}
