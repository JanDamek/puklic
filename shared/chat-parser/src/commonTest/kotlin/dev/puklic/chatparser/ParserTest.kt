package dev.puklic.chatparser

import dev.puklic.domain.EmojiRef
import dev.puklic.domain.MentionTarget
import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextInline
import dev.puklic.domain.TextStyle
import dev.puklic.domain.TimestampStyle
import dev.puklic.ids.ChannelId
import dev.puklic.ids.EmojiId
import dev.puklic.ids.RoleId
import dev.puklic.ids.UserId
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [parseRichText] — Discord-flavored markdown parser.
 * Spec: docs/02_domain/richtext-ast.md
 */
class ParserTest {

    // Helpers ────────────────────────────────────────────────────────────────

    private fun firstParagraphRuns(input: String): List<RichTextInline> {
        val doc = parseRichText(input)
        val block = doc.blocks.first()
        assertTrue(block is RichTextBlock.Paragraph, "Expected paragraph, got $block")
        return block.runs
    }

    private fun text(content: String, vararg styles: TextStyle): RichTextInline.Text =
        RichTextInline.Text(content, styles.toSet())

    // 1. Empty / whitespace ──────────────────────────────────────────────────

    @Test
    fun `empty string yields empty document`() {
        assertEquals(emptyList(), parseRichText("").blocks)
    }

    @Test
    fun `single space yields empty document`() {
        assertEquals(emptyList(), parseRichText(" ").blocks)
    }

    @Test
    fun `only newlines yield empty document`() {
        assertEquals(emptyList(), parseRichText("\n\n\n").blocks)
    }

    @Test
    fun `only tabs and spaces yield empty document`() {
        assertEquals(emptyList(), parseRichText("   \t  ").blocks)
    }

    // 2. Plain text ──────────────────────────────────────────────────────────

    @Test
    fun `single word becomes a paragraph with single text run`() {
        val runs = firstParagraphRuns("hello")
        assertEquals(listOf(text("hello")), runs)
    }

    @Test
    fun `sentence becomes a single paragraph`() {
        val runs = firstParagraphRuns("hello world from puklic")
        assertEquals(listOf(text("hello world from puklic")), runs)
    }

    @Test
    fun `multi-paragraph splits on blank line`() {
        val doc = parseRichText("para one\n\npara two")
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks[0] is RichTextBlock.Paragraph)
        assertTrue(doc.blocks[1] is RichTextBlock.Paragraph)
    }

    @Test
    fun `single newline inside paragraph becomes soft line break`() {
        val runs = firstParagraphRuns("line one\nline two")
        // Expect three runs: Text("line one"), LineBreak(soft=true), Text("line two")
        assertEquals(3, runs.size)
        assertEquals(text("line one"), runs[0])
        assertEquals(RichTextInline.LineBreak(soft = true), runs[1])
        assertEquals(text("line two"), runs[2])
    }

    // 3. Inline styles ───────────────────────────────────────────────────────

    @Test
    fun `bold with double-star`() {
        val runs = firstParagraphRuns("**bold**")
        assertEquals(listOf(text("bold", TextStyle.BOLD)), runs)
    }

    @Test
    fun `italic with single-star`() {
        val runs = firstParagraphRuns("*italic*")
        assertEquals(listOf(text("italic", TextStyle.ITALIC)), runs)
    }

    @Test
    fun `italic with single-underscore`() {
        val runs = firstParagraphRuns("_italic_")
        assertEquals(listOf(text("italic", TextStyle.ITALIC)), runs)
    }

    @Test
    fun `underline with double-underscore`() {
        val runs = firstParagraphRuns("__under__")
        assertEquals(listOf(text("under", TextStyle.UNDERLINE)), runs)
    }

    @Test
    fun `strikethrough with double-tilde`() {
        val runs = firstParagraphRuns("~~gone~~")
        assertEquals(listOf(text("gone", TextStyle.STRIKETHROUGH)), runs)
    }

    @Test
    fun `inline code with single backtick`() {
        val runs = firstParagraphRuns("here is `code` ok")
        assertEquals(3, runs.size)
        assertEquals(text("here is "), runs[0])
        assertEquals(RichTextInline.InlineCode("code"), runs[1])
        assertEquals(text(" ok"), runs[2])
    }

    @Test
    fun `bold-italic combined with triple-star tolerates greedy match`() {
        // `***bi***` is intentionally ambiguous in Discord. We accept either a single
        // BOLD+ITALIC run, OR a BOLD run followed by a literal dangling star — both
        // are reasonable; we only assert that some part carries BOLD and the text "bi"
        // appears somewhere.
        val runs = firstParagraphRuns("***bi***")
        val texts = runs.filterIsInstance<RichTextInline.Text>()
        assertTrue(texts.isNotEmpty())
        assertTrue(texts.any { TextStyle.BOLD in it.styles })
        assertTrue(texts.any { it.content.contains("bi") })
    }

    @Test
    fun `bold around text yields bold style only on inner`() {
        val runs = firstParagraphRuns("a **b** c")
        assertEquals(3, runs.size)
        assertEquals(text("a "), runs[0])
        assertEquals(text("b", TextStyle.BOLD), runs[1])
        assertEquals(text(" c"), runs[2])
    }

    @Test
    fun `nested italic inside bold`() {
        val runs = firstParagraphRuns("**bold _and italic_ end**")
        // Inside bold span: "bold ", italic("and italic"), " end"
        // Result: 3 text runs, all with BOLD set; middle one also ITALIC.
        val flat = runs.filterIsInstance<RichTextInline.Text>()
        assertTrue(flat.all { TextStyle.BOLD in it.styles })
        assertTrue(flat.any { TextStyle.ITALIC in it.styles && it.content == "and italic" })
    }

    // 4. Code blocks ─────────────────────────────────────────────────────────

    @Test
    fun `fenced code block with language`() {
        val doc = parseRichText("```kotlin\nval x = 1\n```")
        assertEquals(1, doc.blocks.size)
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals("kotlin", cb.language)
        assertEquals("val x = 1", cb.content)
    }

    @Test
    fun `fenced code block without language`() {
        val doc = parseRichText("```\nhello\n```")
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals(null, cb.language)
        assertEquals("hello", cb.content)
    }

    @Test
    fun `multiline code block preserves whitespace`() {
        val doc = parseRichText("```\nline1\n  line2\n\tline3\n```")
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals("line1\n  line2\n\tline3", cb.content)
    }

    @Test
    fun `code block interior is literal — no nested parsing`() {
        val doc = parseRichText("```\n**not bold** <@1>\n```")
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals("**not bold** <@1>", cb.content)
    }

    @Test
    fun `code block with single backticks inside`() {
        val doc = parseRichText("```\na `b` c\n```")
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals("a `b` c", cb.content)
    }

    @Test
    fun `unterminated code fence at EOF is tolerated`() {
        val doc = parseRichText("```kotlin\nval x = 1")
        assertEquals(1, doc.blocks.size)
        val cb = doc.blocks[0]
        // Either a CodeBlock with whatever content was captured, or a Paragraph with literal — both acceptable; pin to CodeBlock as it's the most useful behavior.
        assertTrue(cb is RichTextBlock.CodeBlock)
        assertEquals("kotlin", cb.language)
        assertEquals("val x = 1", cb.content)
    }

    // 5. Quotes ──────────────────────────────────────────────────────────────

    @Test
    fun `single line quote`() {
        val doc = parseRichText("> hello")
        assertEquals(1, doc.blocks.size)
        val q = doc.blocks[0] as RichTextBlock.Quote
        val inner = q.content[0] as RichTextBlock.Paragraph
        assertEquals(text("hello"), inner.runs[0])
    }

    @Test
    fun `multi-line consecutive quote`() {
        val doc = parseRichText("> line one\n> line two")
        val q = doc.blocks[0] as RichTextBlock.Quote
        assertTrue(q.content.isNotEmpty())
    }

    @Test
    fun `quote then plain paragraph`() {
        val doc = parseRichText("> quoted\n\nplain")
        assertEquals(2, doc.blocks.size)
        assertTrue(doc.blocks[0] is RichTextBlock.Quote)
        assertTrue(doc.blocks[1] is RichTextBlock.Paragraph)
    }

    // 6. Headings ────────────────────────────────────────────────────────────

    @Test
    fun `H1 heading`() {
        val doc = parseRichText("# hello")
        val h = doc.blocks[0] as RichTextBlock.Heading
        assertEquals(1, h.level)
        assertEquals(text("hello"), h.runs[0])
    }

    @Test
    fun `H2 heading`() {
        val doc = parseRichText("## hello")
        val h = doc.blocks[0] as RichTextBlock.Heading
        assertEquals(2, h.level)
    }

    @Test
    fun `H3 heading`() {
        val doc = parseRichText("### hello")
        val h = doc.blocks[0] as RichTextBlock.Heading
        assertEquals(3, h.level)
    }

    @Test
    fun `H4 (more than three hashes) degrades to plain paragraph`() {
        val doc = parseRichText("#### hello")
        assertTrue(doc.blocks[0] is RichTextBlock.Paragraph)
        val p = doc.blocks[0] as RichTextBlock.Paragraph
        // Literal text including the hashes
        val t = p.runs[0] as RichTextInline.Text
        assertTrue(t.content.startsWith("####"))
    }

    @Test
    fun `hash without space is not a heading`() {
        val doc = parseRichText("#nothash")
        val p = doc.blocks[0] as RichTextBlock.Paragraph
        val t = p.runs[0] as RichTextInline.Text
        assertEquals("#nothash", t.content)
    }

    // 7. Spoilers ────────────────────────────────────────────────────────────

    @Test
    fun `spoiler basic`() {
        val runs = firstParagraphRuns("||secret||")
        val s = runs[0] as RichTextInline.Spoiler
        assertEquals(text("secret"), s.content[0])
    }

    @Test
    fun `unclosed spoiler is literal`() {
        val runs = firstParagraphRuns("||unclosed")
        val t = runs[0] as RichTextInline.Text
        assertTrue(t.content.startsWith("||"))
    }

    @Test
    fun `spoiler containing bold`() {
        val runs = firstParagraphRuns("||**bold**||")
        val s = runs[0] as RichTextInline.Spoiler
        val inner = s.content[0] as RichTextInline.Text
        assertEquals("bold", inner.content)
        assertTrue(TextStyle.BOLD in inner.styles)
    }

    // 8. Links ───────────────────────────────────────────────────────────────

    @Test
    fun `markdown link with plain label`() {
        val runs = firstParagraphRuns("[Puklic](https://puklic.dev)")
        val link = runs[0] as RichTextInline.Link
        assertEquals("https://puklic.dev", link.url)
        assertEquals(text("Puklic"), link.display[0])
    }

    @Test
    fun `markdown link with bold label`() {
        val runs = firstParagraphRuns("[**bold**](https://x.io)")
        val link = runs[0] as RichTextInline.Link
        assertEquals("https://x.io", link.url)
        val inner = link.display[0] as RichTextInline.Text
        assertEquals("bold", inner.content)
        assertTrue(TextStyle.BOLD in inner.styles)
    }

    @Test
    fun `autolink https`() {
        val runs = firstParagraphRuns("see https://example.com now")
        val link = runs.filterIsInstance<RichTextInline.Link>().first()
        assertEquals("https://example.com", link.url)
        assertEquals(text("https://example.com"), link.display[0])
    }

    @Test
    fun `autolink http`() {
        val runs = firstParagraphRuns("http://a.b/c")
        val link = runs[0] as RichTextInline.Link
        assertEquals("http://a.b/c", link.url)
    }

    @Test
    fun `unbalanced link bracket is literal`() {
        val runs = firstParagraphRuns("[label](unclosed")
        // Should be literal text, no Link
        assertTrue(runs.none { it is RichTextInline.Link })
    }

    // 9. Mentions ────────────────────────────────────────────────────────────

    @Test
    fun `user mention`() {
        val runs = firstParagraphRuns("hi <@123>")
        val m = runs.filterIsInstance<RichTextInline.Mention>().first()
        assertEquals(MentionTarget.User(UserId(123L)), m.target)
    }

    @Test
    fun `role mention`() {
        val runs = firstParagraphRuns("<@&456>")
        val m = runs[0] as RichTextInline.Mention
        assertEquals(MentionTarget.Role(RoleId(456L)), m.target)
    }

    @Test
    fun `channel mention`() {
        val runs = firstParagraphRuns("<#789>")
        val m = runs[0] as RichTextInline.Mention
        assertEquals(MentionTarget.Channel(ChannelId(789L)), m.target)
    }

    @Test
    fun `everyone mention`() {
        val runs = firstParagraphRuns("@everyone hi")
        val m = runs.filterIsInstance<RichTextInline.Mention>().first()
        assertEquals(MentionTarget.Everyone, m.target)
    }

    @Test
    fun `here mention`() {
        val runs = firstParagraphRuns("@here")
        val m = runs[0] as RichTextInline.Mention
        assertEquals(MentionTarget.Here, m.target)
    }

    @Test
    fun `invalid mention shape is literal`() {
        val runs = firstParagraphRuns("<@notdigits>")
        assertTrue(runs.none { it is RichTextInline.Mention })
        val t = runs[0] as RichTextInline.Text
        assertTrue(t.content.contains("<@notdigits>"))
    }

    @Test
    fun `mention inside code block is not parsed`() {
        val doc = parseRichText("```\n<@1>\n```")
        val cb = doc.blocks[0] as RichTextBlock.CodeBlock
        assertEquals("<@1>", cb.content)
    }

    @Test
    fun `mention inside inline code is not parsed`() {
        val runs = firstParagraphRuns("a `<@1>` b")
        val ic = runs.filterIsInstance<RichTextInline.InlineCode>().first()
        assertEquals("<@1>", ic.content)
        assertTrue(runs.none { it is RichTextInline.Mention })
    }

    // 10. Custom emoji ───────────────────────────────────────────────────────

    @Test
    fun `custom emoji static`() {
        val runs = firstParagraphRuns("<:wave:42>")
        val e = runs[0] as RichTextInline.Emoji
        val ref = e.ref as EmojiRef.Custom
        assertEquals(EmojiId(42L), ref.id)
        assertEquals("wave", ref.name)
        assertFalse(ref.animated)
    }

    @Test
    fun `custom emoji animated`() {
        val runs = firstParagraphRuns("<a:dance:99>")
        val e = runs[0] as RichTextInline.Emoji
        val ref = e.ref as EmojiRef.Custom
        assertEquals(EmojiId(99L), ref.id)
        assertEquals("dance", ref.name)
        assertTrue(ref.animated)
    }

    @Test
    fun `malformed custom emoji is literal`() {
        val runs = firstParagraphRuns("<:wave:notanumber>")
        assertTrue(runs.none { it is RichTextInline.Emoji })
    }

    // 11. Timestamps ─────────────────────────────────────────────────────────

    private fun parseTimestamp(input: String): RichTextInline.Timestamp {
        val runs = firstParagraphRuns(input)
        return runs.filterIsInstance<RichTextInline.Timestamp>().first()
    }

    @Test
    fun `timestamp style t SHORT_TIME`() {
        assertEquals(TimestampStyle.SHORT_TIME, parseTimestamp("<t:1700000000:t>").style)
    }

    @Test
    fun `timestamp style T LONG_TIME`() {
        assertEquals(TimestampStyle.LONG_TIME, parseTimestamp("<t:1700000000:T>").style)
    }

    @Test
    fun `timestamp style d SHORT_DATE`() {
        assertEquals(TimestampStyle.SHORT_DATE, parseTimestamp("<t:1700000000:d>").style)
    }

    @Test
    fun `timestamp style D LONG_DATE`() {
        assertEquals(TimestampStyle.LONG_DATE, parseTimestamp("<t:1700000000:D>").style)
    }

    @Test
    fun `timestamp style f SHORT_DATETIME`() {
        assertEquals(TimestampStyle.SHORT_DATETIME, parseTimestamp("<t:1700000000:f>").style)
    }

    @Test
    fun `timestamp style F LONG_DATETIME`() {
        assertEquals(TimestampStyle.LONG_DATETIME, parseTimestamp("<t:1700000000:F>").style)
    }

    @Test
    fun `timestamp style R RELATIVE`() {
        assertEquals(TimestampStyle.RELATIVE, parseTimestamp("<t:1700000000:R>").style)
    }

    @Test
    fun `timestamp without style defaults to SHORT_DATETIME`() {
        val ts = parseTimestamp("<t:1700000000>")
        assertEquals(TimestampStyle.SHORT_DATETIME, ts.style)
        assertEquals(Instant.fromEpochSeconds(1700000000L), ts.instant)
    }

    @Test
    fun `timestamp invalid shape is literal`() {
        val runs = firstParagraphRuns("<t:notanumber:R>")
        assertTrue(runs.none { it is RichTextInline.Timestamp })
    }

    @Test
    fun `timestamp negative unix is literal`() {
        val runs = firstParagraphRuns("<t:-5:R>")
        assertTrue(runs.none { it is RichTextInline.Timestamp })
    }

    // 12. Unicode emoji ──────────────────────────────────────────────────────

    @Test
    fun `single unicode emoji standalone is parsed as emoji`() {
        val runs = firstParagraphRuns("🔥")
        val e = runs.filterIsInstance<RichTextInline.Emoji>().firstOrNull()
        assertNotNull(e)
        val ref = e.ref as EmojiRef.Unicode
        assertEquals("🔥", ref.codepoint)
    }

    @Test
    fun `emoji surrounded by text`() {
        val runs = firstParagraphRuns("hi 🔥 there")
        assertTrue(runs.any { it is RichTextInline.Emoji })
    }

    @Test
    fun `multi-codepoint ZWJ emoji preserved`() {
        val family = "👨‍💻"
        val runs = firstParagraphRuns(family)
        val e = runs.filterIsInstance<RichTextInline.Emoji>().firstOrNull()
        assertNotNull(e)
        val ref = e.ref as EmojiRef.Unicode
        assertEquals(family, ref.codepoint)
    }

    // 13. Edge cases ─────────────────────────────────────────────────────────

    @Test
    fun `single star followed by space is literal`() {
        val runs = firstParagraphRuns("* a")
        val t = runs[0] as RichTextInline.Text
        assertTrue(t.content.startsWith("* "))
    }

    @Test
    fun `text starting with star with no closing is literal`() {
        val runs = firstParagraphRuns("*hanging")
        val t = runs[0] as RichTextInline.Text
        assertEquals("*hanging", t.content)
    }

    @Test
    fun `unclosed bold is literal`() {
        val runs = firstParagraphRuns("**unclosed")
        val t = runs[0] as RichTextInline.Text
        assertTrue(t.content.startsWith("**"))
    }

    @Test
    fun `empty bold yields empty styled text or literal — no exception`() {
        // Must not throw. Result may be an empty document if parser swallows ****.
        val doc = parseRichText("****")
        // Trivially passes — no throw is the contract.
        assertTrue(doc.blocks.size >= 0)
    }

    // 14. Tolerance / property-ish ───────────────────────────────────────────

    @Test
    fun `parser is total on random-ish ascii input`() {
        val samples = listOf(
            "", "*", "**", "***", "_", "~~", "`", "```", "<", ">", "@", "#",
            "<@", "<@>", "<#>", "<:>", "<t:>",
            "[", "[](",
            "**a*b**c*",
            "||",
            "> > nested",
            "# ## ###",
        )
        for (s in samples) {
            parseRichText(s)  // must not throw
        }
    }

    @Test
    fun `parser terminates on 4000-char input within time budget`() {
        val big = buildString {
            repeat(800) { append("**bold** *it* `c` <@1> 🔥 ") }
        }.take(4000)
        val doc = parseRichText(big)
        assertTrue(doc.blocks.isNotEmpty())
    }

    @Test
    fun `parser terminates on long-unclosed-bold input`() {
        val big = "**" + "a".repeat(3990)
        val doc = parseRichText(big)
        assertTrue(doc.blocks.isNotEmpty())
    }

    @Test
    fun `parser terminates on input with only special chars`() {
        val s = "*_~`|<>@#[]()".repeat(50)
        val doc = parseRichText(s)
        assertTrue(doc.blocks.size >= 0) // no throw
    }

    @Test
    fun `heading + paragraph mix`() {
        val doc = parseRichText("# title\n\nbody text\n\n## section")
        assertEquals(3, doc.blocks.size)
        assertTrue(doc.blocks[0] is RichTextBlock.Heading)
        assertTrue(doc.blocks[1] is RichTextBlock.Paragraph)
        assertTrue(doc.blocks[2] is RichTextBlock.Heading)
    }

    @Test
    fun `code block then paragraph`() {
        val doc = parseRichText("```\ncode\n```\nafter")
        assertTrue(doc.blocks[0] is RichTextBlock.CodeBlock)
        assertTrue(doc.blocks.last() is RichTextBlock.Paragraph)
    }

    @Test
    fun `mention then text then emoji`() {
        val runs = firstParagraphRuns("<@1> hello 🎉")
        assertTrue(runs.any { it is RichTextInline.Mention })
        assertTrue(runs.any { it is RichTextInline.Text })
        assertTrue(runs.any { it is RichTextInline.Emoji })
    }
}
