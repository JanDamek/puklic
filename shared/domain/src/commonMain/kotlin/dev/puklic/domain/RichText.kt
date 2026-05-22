package dev.puklic.domain

import kotlinx.datetime.Instant

data class RichTextDocument(val blocks: List<RichTextBlock>)

sealed interface RichTextBlock {
    data class Paragraph(val runs: kotlin.collections.List<RichTextInline>) : RichTextBlock
    data class CodeBlock(val language: String?, val content: String) : RichTextBlock
    data class Quote(val content: kotlin.collections.List<RichTextBlock>) : RichTextBlock
    data class Heading(val level: Int, val runs: kotlin.collections.List<RichTextInline>) : RichTextBlock
    data class List(val ordered: Boolean, val items: kotlin.collections.List<ListItem>) : RichTextBlock
    data class ListItem(val content: kotlin.collections.List<RichTextBlock>) : RichTextBlock
}

sealed interface RichTextInline {
    data class Text(val content: String, val styles: Set<TextStyle>) : RichTextInline
    data class InlineCode(val content: String) : RichTextInline
    data class Link(val url: String, val display: kotlin.collections.List<RichTextInline>) : RichTextInline
    data class Mention(val target: MentionTarget) : RichTextInline
    data class Emoji(val ref: EmojiRef) : RichTextInline
    data class Spoiler(val content: kotlin.collections.List<RichTextInline>) : RichTextInline
    data class Timestamp(val instant: Instant, val style: TimestampStyle) : RichTextInline
    data class LineBreak(val soft: Boolean) : RichTextInline
}

enum class TextStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
}

enum class TimestampStyle {
    SHORT_TIME,
    LONG_TIME,
    SHORT_DATE,
    LONG_DATE,
    SHORT_DATETIME,
    LONG_DATETIME,
    RELATIVE,
}
