package dev.puklic.domain

import dev.puklic.ids.ChannelId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec: docs/02_domain/richtext-ast.md — "AST model"
 *
 * All AST types live in :shared:domain (as per spec section "Lives in :shared:domain (types)").
 * The parser lives in :shared:chat-parser (tested there separately).
 *
 * Covers:
 *  - RichTextDocument construction: empty and non-empty blocks list
 *  - RichTextBlock sealed hierarchy exhaustiveness via when() — 6 subtypes
 *  - RichTextInline sealed hierarchy exhaustiveness via when() — 8 subtypes
 *  - TextStyle enum: BOLD, ITALIC, UNDERLINE, STRIKETHROUGH
 *  - TimestampStyle enum: 7 values per spec
 *  - Each concrete block/inline data class construction and field preservation
 *  - Structural equality for key types
 */
class RichTextDocumentTest {

    // ── RichTextDocument ──────────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class RichTextDocument(val blocks: List<RichTextBlock>)"
     */
    @Test
    fun `RichTextDocument with empty blocks list is valid`() {
        val doc = RichTextDocument(blocks = emptyList())
        assertTrue(doc.blocks.isEmpty())
    }

    @Test
    fun `RichTextDocument preserves blocks in order`() {
        val paragraph = RichTextBlock.Paragraph(
            runs = listOf(RichTextInline.Text("Hello", emptySet())),
        )
        val codeBlock = RichTextBlock.CodeBlock(language = "kotlin", content = "println()")
        val doc = RichTextDocument(blocks = listOf(paragraph, codeBlock))
        assertEquals(2, doc.blocks.size)
        assertEquals(paragraph, doc.blocks[0])
        assertEquals(codeBlock, doc.blocks[1])
    }

    @Test
    fun `two RichTextDocument instances with identical blocks are equal`() {
        val a = RichTextDocument(blocks = listOf(RichTextBlock.CodeBlock(null, "x")))
        val b = RichTextDocument(blocks = listOf(RichTextBlock.CodeBlock(null, "x")))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── RichTextBlock sealed hierarchy exhaustiveness ─────────────────────────

    /**
     * Spec: richtext-ast.md — RichTextBlock sealed interface with 6 subtypes:
     *   Paragraph, CodeBlock, Quote, Heading, List, ListItem
     * No else branch — compiler enforces all branches present.
     */
    @Test
    fun `sealed RichTextBlock when expression covers all six subtypes without else`() {
        val blocks: List<RichTextBlock> = listOf(
            RichTextBlock.Paragraph(emptyList()),
            RichTextBlock.CodeBlock(language = "kotlin", content = "val x = 1"),
            RichTextBlock.Quote(content = listOf(RichTextBlock.Paragraph(emptyList()))),
            RichTextBlock.Heading(level = 1, runs = emptyList()),
            RichTextBlock.List(ordered = false, items = emptyList()),
            RichTextBlock.ListItem(content = emptyList()),
        )

        val labels = blocks.map { block ->
            when (block) {
                is RichTextBlock.Paragraph -> "paragraph"
                is RichTextBlock.CodeBlock -> "code_block"
                is RichTextBlock.Quote     -> "quote"
                is RichTextBlock.Heading   -> "heading"
                is RichTextBlock.List      -> "list"
                is RichTextBlock.ListItem  -> "list_item"
            }
        }

        assertEquals(
            listOf("paragraph", "code_block", "quote", "heading", "list", "list_item"),
            labels,
        )
    }

    // ── RichTextBlock.Paragraph ───────────────────────────────────────────────

    @Test
    fun `Paragraph with text runs preserves all inline elements`() {
        val runs = listOf(
            RichTextInline.Text("Bold text", setOf(TextStyle.BOLD)),
            RichTextInline.Text(" normal text", emptySet()),
        )
        val para = RichTextBlock.Paragraph(runs)
        assertEquals(2, para.runs.size)
        assertEquals(runs, para.runs)
    }

    @Test
    fun `Paragraph with empty runs list is valid`() {
        val para = RichTextBlock.Paragraph(emptyList())
        assertTrue(para.runs.isEmpty())
    }

    // ── RichTextBlock.CodeBlock ───────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class CodeBlock(val language: String?, val content: String)"
     *   CodeBlock.content preserves whitespace and newlines 1:1
     */
    @Test
    fun `CodeBlock preserves language and content including whitespace`() {
        val content = "fun main() {\n    println(\"hello\")\n}"
        val block = RichTextBlock.CodeBlock(language = "kotlin", content = content)
        assertEquals("kotlin", block.language)
        assertEquals(content, block.content)
    }

    @Test
    fun `CodeBlock with null language is valid for fenced block without language hint`() {
        val block = RichTextBlock.CodeBlock(language = null, content = "some code")
        assertNull(block.language)
        assertEquals("some code", block.content)
    }

    @Test
    fun `CodeBlock with empty content is valid`() {
        val block = RichTextBlock.CodeBlock(language = null, content = "")
        assertEquals("", block.content)
    }

    // ── RichTextBlock.Quote ───────────────────────────────────────────────────

    @Test
    fun `Quote contains nested RichTextBlocks`() {
        val inner = RichTextBlock.Paragraph(
            listOf(RichTextInline.Text("Quoted text", emptySet())),
        )
        val quote = RichTextBlock.Quote(content = listOf(inner))
        assertEquals(1, quote.content.size)
        assertEquals(inner, quote.content[0])
    }

    // ── RichTextBlock.Heading ─────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class Heading(val level: Int, ...) // # ## ###, level 1-3"
     */
    @Test
    fun `Heading level 1 is preserved`() {
        val heading = RichTextBlock.Heading(level = 1, runs = listOf(RichTextInline.Text("Title", emptySet())))
        assertEquals(1, heading.level)
    }

    @Test
    fun `Heading level 3 is preserved`() {
        val heading = RichTextBlock.Heading(level = 3, runs = emptyList())
        assertEquals(3, heading.level)
    }

    // ── RichTextBlock.List and ListItem ───────────────────────────────────────

    @Test
    fun `List ordered true is an ordered list`() {
        val list = RichTextBlock.List(ordered = true, items = emptyList())
        assertTrue(list.ordered)
    }

    @Test
    fun `List ordered false is an unordered list`() {
        val list = RichTextBlock.List(ordered = false, items = emptyList())
        assertFalse(list.ordered)
    }

    @Test
    fun `ListItem with block content is preserved`() {
        val item = RichTextBlock.ListItem(
            content = listOf(RichTextBlock.Paragraph(emptyList())),
        )
        assertEquals(1, item.content.size)
    }

    // ── RichTextInline sealed hierarchy exhaustiveness ────────────────────────

    /**
     * Spec: richtext-ast.md — RichTextInline sealed interface with 8 subtypes:
     *   Text, InlineCode, Link, Mention, Emoji, Spoiler, Timestamp, LineBreak
     * No else branch required.
     */
    @Test
    fun `sealed RichTextInline when expression covers all eight subtypes without else`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val inlines: List<RichTextInline> = listOf(
            RichTextInline.Text("hello", emptySet()),
            RichTextInline.InlineCode("x"),
            RichTextInline.Link("https://example.com", listOf(RichTextInline.Text("link", emptySet()))),
            RichTextInline.Mention(MentionTarget.User(UserId(1L))),
            RichTextInline.Emoji(EmojiRef.Unicode("😊")),
            RichTextInline.Spoiler(listOf(RichTextInline.Text("secret", emptySet()))),
            RichTextInline.Timestamp(instant, TimestampStyle.RELATIVE),
            RichTextInline.LineBreak(soft = false),
        )

        val labels = inlines.map { inline ->
            when (inline) {
                is RichTextInline.Text       -> "text"
                is RichTextInline.InlineCode -> "inline_code"
                is RichTextInline.Link       -> "link"
                is RichTextInline.Mention    -> "mention"
                is RichTextInline.Emoji      -> "emoji"
                is RichTextInline.Spoiler    -> "spoiler"
                is RichTextInline.Timestamp  -> "timestamp"
                is RichTextInline.LineBreak  -> "line_break"
            }
        }

        assertEquals(
            listOf("text", "inline_code", "link", "mention", "emoji", "spoiler", "timestamp", "line_break"),
            labels,
        )
    }

    // ── RichTextInline.Text + TextStyle ───────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class Text(val content: String, val styles: Set<TextStyle>)"
     *   TextStyle can combine (bold+italic)
     */
    @Test
    fun `Text with BOLD style set preserved`() {
        val text = RichTextInline.Text("bold text", setOf(TextStyle.BOLD))
        assertEquals("bold text", text.content)
        assertEquals(setOf(TextStyle.BOLD), text.styles)
    }

    @Test
    fun `Text with combined BOLD and ITALIC styles is preserved`() {
        val styles = setOf(TextStyle.BOLD, TextStyle.ITALIC)
        val text = RichTextInline.Text("bold italic", styles)
        assertEquals(2, text.styles.size)
        assertTrue(text.styles.contains(TextStyle.BOLD))
        assertTrue(text.styles.contains(TextStyle.ITALIC))
    }

    @Test
    fun `Text with empty styles set is plain text`() {
        val text = RichTextInline.Text("plain", emptySet())
        assertTrue(text.styles.isEmpty())
    }

    @Test
    fun `TextStyle enum has exactly BOLD ITALIC UNDERLINE STRIKETHROUGH`() {
        val expected = setOf(TextStyle.BOLD, TextStyle.ITALIC, TextStyle.UNDERLINE, TextStyle.STRIKETHROUGH)
        assertEquals(4, TextStyle.entries.size, "TextStyle must have exactly 4 entries per spec")
        assertEquals(expected, TextStyle.entries.toSet())
    }

    // ── RichTextInline.InlineCode ─────────────────────────────────────────────

    @Test
    fun `InlineCode preserves content string`() {
        val code = RichTextInline.InlineCode("val x = 42")
        assertEquals("val x = 42", code.content)
    }

    // ── RichTextInline.Link ───────────────────────────────────────────────────

    @Test
    fun `Link preserves url and display runs`() {
        val display = listOf(RichTextInline.Text("Click here", emptySet()))
        val link = RichTextInline.Link("https://example.com", display)
        assertEquals("https://example.com", link.url)
        assertEquals(display, link.display)
    }

    @Test
    fun `Link with empty display list is valid for bare url`() {
        val link = RichTextInline.Link("https://example.com", emptyList())
        assertTrue(link.display.isEmpty())
    }

    // ── RichTextInline.Mention ────────────────────────────────────────────────

    @Test
    fun `Mention carries MentionTarget without resolved name`() {
        val mention = RichTextInline.Mention(MentionTarget.Role(RoleId(777L)))
        assertEquals(MentionTarget.Role(RoleId(777L)), mention.target)
    }

    @Test
    fun `Mention with Everyone target is preserved`() {
        val mention = RichTextInline.Mention(MentionTarget.Everyone)
        assertEquals(MentionTarget.Everyone, mention.target)
    }

    @Test
    fun `Mention with Channel target is preserved`() {
        val mention = RichTextInline.Mention(MentionTarget.Channel(ChannelId(123L)))
        assertEquals(MentionTarget.Channel(ChannelId(123L)), mention.target)
    }

    // ── RichTextInline.Emoji ──────────────────────────────────────────────────

    @Test
    fun `Emoji carries EmojiRef Unicode`() {
        val emoji = RichTextInline.Emoji(EmojiRef.Unicode("🔥"))
        assertEquals(EmojiRef.Unicode("🔥"), emoji.ref)
    }

    @Test
    fun `Emoji carries EmojiRef Custom with animated flag`() {
        val ref = EmojiRef.Custom(dev.puklic.ids.EmojiId(1L), "fire_animated", true)
        val emoji = RichTextInline.Emoji(ref)
        assertEquals(ref, emoji.ref)
    }

    // ── RichTextInline.Spoiler ────────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "Spoiler may contain arbitrary inline elements"
     */
    @Test
    fun `Spoiler with nested inline elements is preserved`() {
        val inner = listOf(
            RichTextInline.Text("secret", setOf(TextStyle.BOLD)),
            RichTextInline.Emoji(EmojiRef.Unicode("🤫")),
        )
        val spoiler = RichTextInline.Spoiler(inner)
        assertEquals(2, spoiler.content.size)
        assertEquals(inner, spoiler.content)
    }

    // ── RichTextInline.Timestamp ──────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — TimestampStyle enum with 7 values
     */
    @Test
    fun `TimestampStyle enum has exactly 7 values per spec`() {
        val expected = setOf(
            TimestampStyle.SHORT_TIME,
            TimestampStyle.LONG_TIME,
            TimestampStyle.SHORT_DATE,
            TimestampStyle.LONG_DATE,
            TimestampStyle.SHORT_DATETIME,
            TimestampStyle.LONG_DATETIME,
            TimestampStyle.RELATIVE,
        )
        assertEquals(7, TimestampStyle.entries.size, "TimestampStyle must have exactly 7 entries per spec")
        assertEquals(expected, TimestampStyle.entries.toSet())
    }

    @Test
    fun `Timestamp preserves instant and style`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val ts = RichTextInline.Timestamp(instant, TimestampStyle.LONG_DATETIME)
        assertEquals(instant, ts.instant)
        assertEquals(TimestampStyle.LONG_DATETIME, ts.style)
    }

    // ── RichTextInline.LineBreak ──────────────────────────────────────────────

    /**
     * Spec: richtext-ast.md — "data class LineBreak(val soft: Boolean) // \n vs hard break"
     */
    @Test
    fun `LineBreak soft true is a soft newline`() {
        val lb = RichTextInline.LineBreak(soft = true)
        assertTrue(lb.soft)
    }

    @Test
    fun `LineBreak soft false is a hard break`() {
        val lb = RichTextInline.LineBreak(soft = false)
        assertFalse(lb.soft)
    }

    @Test
    fun `two LineBreak instances with same soft value are equal`() {
        assertEquals(RichTextInline.LineBreak(true), RichTextInline.LineBreak(true))
        assertNotEquals(RichTextInline.LineBreak(true), RichTextInline.LineBreak(false))
    }
}
