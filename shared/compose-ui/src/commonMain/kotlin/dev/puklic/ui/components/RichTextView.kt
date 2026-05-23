package dev.puklic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MentionTarget
import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.RichTextInline
import dev.puklic.domain.TextStyle
import dev.puklic.ui.resolvers.EmojiDisplay
import dev.puklic.ui.resolvers.LocalEmojiResolver
import dev.puklic.ui.resolvers.LocalMentionResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private val EmojiSize = 22.dp

/**
 * Renders a [RichTextDocument] per `docs/02_domain/richtext-ast.md`.
 *
 * Inline runs that map to a Compose `SpanStyle` (bold/italic/underline/strikethrough,
 * inline-code, plain-text mentions, timestamps, unicode emoji, links) are flattened into
 * a single `AnnotatedString` per paragraph. Inline runs that require Compose nodes
 * (custom CDN emoji images) split the paragraph into a horizontal `Row` of sub-runs.
 */
@Composable
public fun RichTextView(
    document: RichTextDocument,
    @Suppress("UnusedParameter") onLinkClick: (String) -> Unit,
    @Suppress("UnusedParameter") onMentionClick: (MentionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        document.blocks.forEach { block -> RenderBlock(block) }
    }
}

@Composable
private fun RenderBlock(block: RichTextBlock) {
    when (block) {
        is RichTextBlock.Paragraph -> InlineRow(block.runs)
        is RichTextBlock.Heading -> InlineRow(
            block.runs,
            baseStyle = when (block.level) {
                1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleLarge.fontSize)
                2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleMedium.fontSize)
                else -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.titleSmall.fontSize)
            },
        )
        is RichTextBlock.CodeBlock -> {
            Text(
                text = block.content,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        is RichTextBlock.Quote -> Column(
            modifier = Modifier.padding(start = 8.dp),
        ) { block.content.forEach { RenderBlock(it) } }
        is RichTextBlock.List -> Column {
            block.items.forEachIndexed { idx, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = if (block.ordered) "${idx + 1}. " else "• ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Column { item.content.forEach { RenderBlock(it) } }
                }
            }
        }
        is RichTextBlock.ListItem -> Column { block.content.forEach { RenderBlock(it) } }
    }
}

/**
 * Renders a flat list of inlines, breaking into segments where a custom-emoji image needs
 * to flow inline with text. All text-only segments are merged into one `AnnotatedString`.
 */
@Composable
private fun InlineRow(
    runs: List<RichTextInline>,
    baseStyle: SpanStyle = SpanStyle(),
) {
    val resolvedMentions = resolveMentionLabels(runs)
    val segments = segmentInlines(runs)
    if (segments.size == 1 && segments.single() is InlineSegment.TextRun) {
        val seg = segments.single() as InlineSegment.TextRun
        Text(
            text = buildSegmentText(seg.runs, baseStyle, resolvedMentions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { seg ->
            when (seg) {
                is InlineSegment.TextRun -> Text(
                    text = buildSegmentText(seg.runs, baseStyle, resolvedMentions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is InlineSegment.CustomEmoji -> CustomEmojiInline(seg.ref)
            }
        }
    }
}

@Composable
private fun CustomEmojiInline(ref: EmojiRef.Custom) {
    val resolver = LocalEmojiResolver.current
    val display = resolver.resolve(ref)
    val url = (display as? EmojiDisplay.Custom)?.url ?: defaultCustomEmojiUrl(ref)
    AsyncImage(
        model = url,
        contentDescription = ":${ref.name}:",
        modifier = Modifier.size(EmojiSize).clip(RoundedCornerShape(2.dp)),
    )
}

private fun defaultCustomEmojiUrl(ref: EmojiRef.Custom): String {
    val ext = if (ref.animated) "gif" else "png"
    return "https://cdn.discordapp.com/emojis/${ref.id.value}.$ext?size=32&quality=lossless"
}

private sealed interface InlineSegment {
    data class TextRun(val runs: List<RichTextInline>) : InlineSegment
    data class CustomEmoji(val ref: EmojiRef.Custom) : InlineSegment
}

private fun segmentInlines(runs: List<RichTextInline>): List<InlineSegment> {
    val out = mutableListOf<InlineSegment>()
    val buf = mutableListOf<RichTextInline>()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(InlineSegment.TextRun(buf.toList()))
            buf.clear()
        }
    }
    runs.forEach { run ->
        val customEmoji = (run as? RichTextInline.Emoji)?.ref as? EmojiRef.Custom
        if (customEmoji != null) {
            flush()
            out.add(InlineSegment.CustomEmoji(customEmoji))
        } else {
            buf.add(run)
        }
    }
    flush()
    return out
}

/**
 * Pre-resolves user / role / channel mention display labels synchronously so the
 * AnnotatedString builder (non-suspending) can pick them up. We collect once via
 * `collectAsState` — when the upstream `Flow` updates (e.g. nickname change), the
 * composable recomposes and produces a fresh AnnotatedString.
 */
@Composable
private fun resolveMentionLabels(runs: List<RichTextInline>): MentionLabels {
    val resolver = LocalMentionResolver.current
    val targets = flattenMentions(runs)
    val userIds = targets.filterIsInstance<MentionTarget.User>().map { it.id }.distinct()
    val roleIds = targets.filterIsInstance<MentionTarget.Role>().map { it.id }.distinct()
    val channelIds = targets.filterIsInstance<MentionTarget.Channel>().map { it.id }.distinct()

    val users = mutableMapOf<Long, String>()
    for (id in userIds) {
        val state = resolver.resolveUser(id).collectAsState(initial = null)
        val u = state.value
        if (u != null) users[id.value] = u.globalName ?: u.username
    }

    val roleLabels = mutableMapOf<Long, String>()
    val roleColors = mutableMapOf<Long, Int>()
    for (id in roleIds) {
        val state = resolver.resolveRole(id).collectAsState(initial = null)
        val r = state.value
        if (r != null) {
            roleLabels[id.value] = r.name
            r.colorArgb?.let { roleColors[id.value] = it }
        }
    }

    val channels = mutableMapOf<Long, String>()
    for (id in channelIds) {
        val state = resolver.resolveChannel(id).collectAsState(initial = null)
        val name = state.value?.name
        if (name != null) channels[id.value] = name
    }

    return MentionLabels(users, roleLabels, roleColors, channels)
}

private data class MentionLabels(
    val users: Map<Long, String>,
    val roles: Map<Long, String>,
    val roleColors: Map<Long, Int>,
    val channels: Map<Long, String>,
)

private fun flattenMentions(runs: List<RichTextInline>): List<MentionTarget> {
    val out = mutableListOf<MentionTarget>()
    runs.forEach { run ->
        when (run) {
            is RichTextInline.Mention -> out.add(run.target)
            is RichTextInline.Link -> out.addAll(flattenMentions(run.display))
            is RichTextInline.Spoiler -> out.addAll(flattenMentions(run.content))
            else -> Unit
        }
    }
    return out
}

@Composable
private fun buildSegmentText(
    runs: List<RichTextInline>,
    baseStyle: SpanStyle,
    labels: MentionLabels,
): AnnotatedString {
    val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val mentionFg = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val spoilerBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    return buildAnnotatedString {
        withStyle(baseStyle) {
            appendInlines(runs, labels, mentionBg, mentionFg, linkColor, codeBg, spoilerBg)
        }
    }
}

@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    runs: List<RichTextInline>,
    labels: MentionLabels,
    mentionBg: Color,
    mentionFg: Color,
    linkColor: Color,
    codeBg: Color,
    spoilerBg: Color,
) {
    runs.forEach { run ->
        when (run) {
            is RichTextInline.Text -> withStyle(run.styles.toSpanStyle()) { append(run.content) }
            is RichTextInline.InlineCode -> withStyle(
                SpanStyle(
                    background = codeBg,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            ) { append(run.content) }
            is RichTextInline.Link -> withStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
            ) {
                if (run.display.isEmpty()) append(run.url)
                else appendInlines(run.display, labels, mentionBg, mentionFg, linkColor, codeBg, spoilerBg)
            }
            is RichTextInline.Mention -> withStyle(
                SpanStyle(background = mentionBg, color = mentionFg, fontWeight = FontWeight.Medium),
            ) { append(renderMention(run.target, labels)) }
            is RichTextInline.Emoji -> when (val ref = run.ref) {
                is EmojiRef.Unicode -> append(ref.codepoint)
                is EmojiRef.Custom -> append(":${ref.name}:") // fallback when segmenter inlines text-only
            }
            is RichTextInline.Spoiler -> withStyle(SpanStyle(background = spoilerBg, color = spoilerBg)) {
                appendInlines(run.content, labels, mentionBg, mentionFg, linkColor, codeBg, spoilerBg)
            }
            is RichTextInline.Timestamp -> withStyle(
                SpanStyle(background = codeBg, fontWeight = FontWeight.Medium),
            ) { append(TimestampFormat.discord(run.instant, run.style)) }
            is RichTextInline.LineBreak -> append('\n')
        }
    }
}

private fun renderMention(target: MentionTarget, labels: MentionLabels): String = when (target) {
    is MentionTarget.User -> "@${labels.users[target.id.value] ?: "user"}"
    is MentionTarget.Role -> "@${labels.roles[target.id.value] ?: "role"}"
    is MentionTarget.Channel -> "#${labels.channels[target.id.value] ?: "channel"}"
    MentionTarget.Everyone -> "@everyone"
    MentionTarget.Here -> "@here"
}

private fun Set<TextStyle>.toSpanStyle(): SpanStyle {
    var weight: FontWeight? = null
    var style: FontStyle? = null
    var decoration: TextDecoration? = null
    if (TextStyle.BOLD in this) weight = FontWeight.Bold
    if (TextStyle.ITALIC in this) style = FontStyle.Italic
    val decorations = buildList {
        if (TextStyle.UNDERLINE in this@toSpanStyle) add(TextDecoration.Underline)
        if (TextStyle.STRIKETHROUGH in this@toSpanStyle) add(TextDecoration.LineThrough)
    }
    if (decorations.isNotEmpty()) decoration = TextDecoration.combine(decorations)
    return SpanStyle(fontWeight = weight, fontStyle = style, textDecoration = decoration)
}

// Suppress unused warning for explicit fallback `flowOf` import retention.
@Suppress("unused")
private val placeholderFlow: Flow<Nothing?> = flowOf(null)
