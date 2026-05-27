package dev.puklic.chatparser

import dev.puklic.domain.RichTextBlock
import dev.puklic.domain.RichTextDocument
import dev.puklic.domain.RichTextInline
import dev.puklic.domain.TextStyle

/**
 * Parses Discord-flavored markdown into a [RichTextDocument] AST.
 *
 * Pure function. No IO. No exceptions on malformed input — tolerant.
 * Suitable for [kotlinx.coroutines.Dispatchers.Default].
 *
 * Spec: docs/02_domain/richtext-ast.md
 *
 * Why hand-rolled (not a markdown library): Discord's dialect diverges from
 * CommonMark in load-bearing ways (`__x__` is underline not bold; `||x||` is
 * spoiler; `>>> ` block quote; Discord-specific inline tokens `<@id>`,
 * `<#id>`, `<@&id>`, `<:name:id>`, `<a:name:id>`, `<t:unix:style>`,
 * `@everyone`, `@here`; tolerant on unmatched delimiters). See
 * `docs/01_architecture/adr/0008-chat-parser-library-decision.md`.
 */
public fun parseRichText(raw: String): RichTextDocument {
    if (raw.isBlank()) return RichTextDocument(emptyList())
    val blocks = parseBlocks(raw)
    return RichTextDocument(blocks)
}

// Block-level parser ───────────────────────────────────────────────────────────

internal fun parseBlocks(raw: String): List<RichTextBlock> {
    val lines = raw.split('\n')
    val out = mutableListOf<RichTextBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // Blank line — paragraph separator
            line.isBlank() -> {
                i++
            }
            // Fenced code block
            line.trimStart().startsWith("```") -> {
                val openTrim = line.trimStart()
                val language = openTrim.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val contentLines = mutableListOf<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trimStart().startsWith("```")) {
                        closed = true
                        break
                    }
                    contentLines.add(lines[j])
                    j++
                }
                out.add(RichTextBlock.CodeBlock(language, contentLines.joinToString("\n")))
                i = if (closed) j + 1 else j
            }
            // Triple-quote block — consumes rest of message
            line.startsWith(">>> ") || line == ">>>" -> {
                val first = if (line == ">>>") "" else line.substring(4)
                val rest = mutableListOf(first)
                var j = i + 1
                while (j < lines.size) {
                    rest.add(lines[j])
                    j++
                }
                val inner = parseBlocks(rest.joinToString("\n"))
                out.add(RichTextBlock.Quote(inner))
                i = j
            }
            // Quote block — consume consecutive `>` lines
            line.startsWith("> ") || line == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].startsWith("> ") || lines[i] == ">")) {
                    quoteLines.add(if (lines[i] == ">") "" else lines[i].substring(2))
                    i++
                }
                val inner = parseBlocks(quoteLines.joinToString("\n"))
                out.add(RichTextBlock.Quote(inner))
            }
            // Bullet list — consecutive `- `, `* `, `+ ` or `• ` lines
            isBulletLine(line) -> {
                val items = mutableListOf<RichTextBlock.ListItem>()
                while (i < lines.size && isBulletLine(lines[i])) {
                    val content = lines[i].substring(bulletMarkerLength(lines[i]))
                    items.add(RichTextBlock.ListItem(listOf(RichTextBlock.Paragraph(parseInlines(content)))))
                    i++
                }
                out.add(RichTextBlock.List(ordered = false, items = items))
            }
            // Heading 1..3 — requires `# ` `## ` `### ` (with space)
            isHeadingLine(line) -> {
                val level = line.takeWhile { it == '#' }.length
                val rest = line.substring(level + 1) // skip space after hashes
                out.add(RichTextBlock.Heading(level, parseInlines(rest)))
                i++
            }
            else -> {
                // Paragraph — consume consecutive non-blank, non-special lines
                val paraLines = mutableListOf<String>()
                paraLines.add(line)
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank()) break
                    if (l.trimStart().startsWith("```")) break
                    if (l.startsWith("> ") || l == ">") break
                    if (l.startsWith(">>> ") || l == ">>>") break
                    if (isHeadingLine(l)) break
                    if (isBulletLine(l)) break
                    paraLines.add(l)
                    i++
                }
                val runs = buildParagraphRuns(paraLines)
                if (runs.isNotEmpty()) {
                    out.add(RichTextBlock.Paragraph(runs))
                }
            }
        }
    }
    return out
}

/**
 * Discord-style bullet markers: `-`, `*`, `+`, `•` followed by a space.
 * The trailing space is required to avoid swallowing bold/italic openers
 * like `**foo**` or `*foo*` at the start of a line.
 */
private fun isBulletLine(line: String): Boolean {
    if (line.length < 2) return false
    val c = line[0]
    if (c != '-' && c != '*' && c != '+' && c != '•') return false
    return line[1] == ' '
}

private fun bulletMarkerLength(@Suppress("UnusedParameter") line: String): Int = 2

private fun isHeadingLine(line: String): Boolean {
    if (!line.startsWith("#")) return false
    val hashes = line.takeWhile { it == '#' }.length
    if (hashes !in 1..3) return false
    if (line.length <= hashes) return false
    return line[hashes] == ' '
}

private fun buildParagraphRuns(lines: List<String>): List<RichTextInline> {
    val out = mutableListOf<RichTextInline>()
    lines.forEachIndexed { idx, l ->
        out.addAll(parseInlines(l))
        if (idx != lines.lastIndex) {
            out.add(RichTextInline.LineBreak(soft = true))
        }
    }
    return out
}

// Inline parser ────────────────────────────────────────────────────────────────

/**
 * Parses a single line / inline-scope string into a list of [RichTextInline].
 * Tolerant — any unmatched delimiter becomes literal text.
 */
internal fun parseInlines(input: String, baseStyles: Set<TextStyle> = emptySet()): List<RichTextInline> {
    if (input.isEmpty()) return emptyList()
    val out = mutableListOf<RichTextInline>()
    val literalBuf = StringBuilder()

    fun flushLiteral() {
        if (literalBuf.isNotEmpty()) {
            out.add(RichTextInline.Text(literalBuf.toString(), baseStyles))
            literalBuf.clear()
        }
    }

    var i = 0
    val n = input.length
    while (i < n) {
        val c = input[i]
        // Inline code: `...`
        if (c == '`') {
            val end = input.indexOf('`', i + 1)
            if (end > i) {
                flushLiteral()
                out.add(RichTextInline.InlineCode(input.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        // Bold: **...**
        if (c == '*' && i + 1 < n && input[i + 1] == '*') {
            val end = findClosing(input, i + 2, "**")
            if (end >= 0) {
                flushLiteral()
                val inner = input.substring(i + 2, end)
                out.addAll(parseInlines(inner, baseStyles + TextStyle.BOLD))
                i = end + 2
                continue
            }
        }
        // Italic: *...*
        if (c == '*' && i + 1 < n && input[i + 1] != '*') {
            val end = findClosing(input, i + 1, "*")
            if (end > i + 1 && !input.substring(i + 1, end).isBlank()) {
                flushLiteral()
                val inner = input.substring(i + 1, end)
                out.addAll(parseInlines(inner, baseStyles + TextStyle.ITALIC))
                i = end + 1
                continue
            }
        }
        // Underline: __...__
        if (c == '_' && i + 1 < n && input[i + 1] == '_') {
            val end = findClosing(input, i + 2, "__")
            if (end >= 0) {
                flushLiteral()
                val inner = input.substring(i + 2, end)
                out.addAll(parseInlines(inner, baseStyles + TextStyle.UNDERLINE))
                i = end + 2
                continue
            }
        }
        // Italic underscore: _..._
        if (c == '_' && (i + 1 >= n || input[i + 1] != '_')) {
            val end = findClosing(input, i + 1, "_")
            if (end > i + 1) {
                flushLiteral()
                val inner = input.substring(i + 1, end)
                out.addAll(parseInlines(inner, baseStyles + TextStyle.ITALIC))
                i = end + 1
                continue
            }
        }
        // Strikethrough: ~~...~~
        if (c == '~' && i + 1 < n && input[i + 1] == '~') {
            val end = findClosing(input, i + 2, "~~")
            if (end >= 0) {
                flushLiteral()
                val inner = input.substring(i + 2, end)
                out.addAll(parseInlines(inner, baseStyles + TextStyle.STRIKETHROUGH))
                i = end + 2
                continue
            }
        }
        // Spoiler: ||...||
        if (c == '|' && i + 1 < n && input[i + 1] == '|') {
            val end = findClosing(input, i + 2, "||")
            if (end >= 0) {
                flushLiteral()
                val inner = parseInlines(input.substring(i + 2, end), baseStyles)
                out.add(RichTextInline.Spoiler(inner))
                i = end + 2
                continue
            }
        }
        // Link: [label](url)
        if (c == '[') {
            val link = tryParseLink(input, i, baseStyles)
            if (link != null) {
                flushLiteral()
                out.add(link.first)
                i = link.second
                continue
            }
        }
        // Autolink http(s)://
        if (c == 'h' && (input.startsWith("https://", i) || input.startsWith("http://", i))) {
            val end = autolinkEnd(input, i)
            if (end > i) {
                flushLiteral()
                val url = input.substring(i, end)
                out.add(
                    RichTextInline.Link(
                        url = url,
                        display = listOf(RichTextInline.Text(url, baseStyles)),
                    ),
                )
                i = end
                continue
            }
        }
        // Discord extensions: <...>
        if (c == '<') {
            val ext = tryParseAngleExt(input, i)
            if (ext != null) {
                flushLiteral()
                out.add(ext.first)
                i = ext.second
                continue
            }
        }
        // @everyone / @here
        if (c == '@') {
            if (input.startsWith("@everyone", i)) {
                flushLiteral()
                out.add(RichTextInline.Mention(dev.puklic.domain.MentionTarget.Everyone))
                i += "@everyone".length
                continue
            }
            if (input.startsWith("@here", i)) {
                flushLiteral()
                out.add(RichTextInline.Mention(dev.puklic.domain.MentionTarget.Here))
                i += "@here".length
                continue
            }
        }
        // Unicode emoji (codepoint outside BMP or in known emoji ranges)
        val cp = input.codePointAtOrNull(i)
        if (cp != null && isLikelyEmojiStart(cp)) {
            val end = emojiSequenceEnd(input, i)
            if (end > i) {
                flushLiteral()
                out.add(RichTextInline.Emoji(dev.puklic.domain.EmojiRef.Unicode(input.substring(i, end))))
                i = end
                continue
            }
        }
        // Default: literal
        literalBuf.append(c)
        i++
    }
    flushLiteral()
    return out
}

private fun findClosing(input: String, from: Int, marker: String): Int {
    var i = from
    val n = input.length
    while (i <= n - marker.length) {
        if (input[i] == '`') {
            // skip over inline code to avoid matching markers inside it
            val end = input.indexOf('`', i + 1)
            if (end > i) {
                i = end + 1
                continue
            }
        }
        if (input.regionMatches(i, marker, 0, marker.length)) return i
        i++
    }
    return -1
}

private fun tryParseLink(
    input: String,
    start: Int,
    baseStyles: Set<TextStyle>,
): Pair<RichTextInline.Link, Int>? {
    // [label](url)
    val labelEnd = input.indexOf(']', start + 1)
    if (labelEnd < 0) return null
    if (labelEnd + 1 >= input.length || input[labelEnd + 1] != '(') return null
    val urlEnd = input.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    val label = input.substring(start + 1, labelEnd)
    val url = input.substring(labelEnd + 2, urlEnd)
    if (url.isBlank()) return null
    val displayRuns = parseInlines(label, baseStyles).ifEmpty {
        listOf(RichTextInline.Text(label, baseStyles))
    }
    return RichTextInline.Link(url = url, display = displayRuns) to (urlEnd + 1)
}

private fun autolinkEnd(input: String, start: Int): Int {
    var i = start
    val n = input.length
    while (i < n) {
        val ch = input[i]
        if (ch.isWhitespace() || ch == '<' || ch == '>' || ch == ')' || ch == ']') break
        i++
    }
    // Trim trailing punctuation that's likely not part of URL
    while (i > start && input[i - 1] in ",.!?;:") i--
    return i
}

private fun tryParseAngleExt(input: String, start: Int): Pair<RichTextInline, Int>? {
    val end = input.indexOf('>', start + 1)
    if (end < 0) return null
    val inner = input.substring(start + 1, end)
    val consumed = end + 1
    // <@&ROLE_ID>
    if (inner.startsWith("@&")) {
        val id = inner.substring(2).toLongOrNull() ?: return null
        if (id < 0) return null
        return RichTextInline.Mention(dev.puklic.domain.MentionTarget.Role(dev.puklic.ids.RoleId(id))) to consumed
    }
    // <@USER_ID>  (also <@!USER_ID> in Discord legacy)
    if (inner.startsWith("@")) {
        val raw = inner.substring(1).removePrefix("!")
        val id = raw.toLongOrNull() ?: return null
        if (id < 0) return null
        return RichTextInline.Mention(dev.puklic.domain.MentionTarget.User(dev.puklic.ids.UserId(id))) to consumed
    }
    // <#CHANNEL_ID>
    if (inner.startsWith("#")) {
        val id = inner.substring(1).toLongOrNull() ?: return null
        if (id < 0) return null
        return RichTextInline.Mention(dev.puklic.domain.MentionTarget.Channel(dev.puklic.ids.ChannelId(id))) to consumed
    }
    // <:name:ID>  static custom emoji
    if (inner.startsWith(":")) {
        val parts = inner.split(":")
        if (parts.size == 3 && parts[0].isEmpty()) {
            val name = parts[1]
            val id = parts[2].toLongOrNull() ?: return null
            if (id < 0 || name.isEmpty()) return null
            return RichTextInline.Emoji(
                dev.puklic.domain.EmojiRef.Custom(dev.puklic.ids.EmojiId(id), name, animated = false),
            ) to consumed
        }
        return null
    }
    // <a:name:ID>  animated custom emoji
    if (inner.startsWith("a:")) {
        val parts = inner.split(":")
        if (parts.size == 3) {
            val name = parts[1]
            val id = parts[2].toLongOrNull() ?: return null
            if (id < 0 || name.isEmpty()) return null
            return RichTextInline.Emoji(
                dev.puklic.domain.EmojiRef.Custom(dev.puklic.ids.EmojiId(id), name, animated = true),
            ) to consumed
        }
        return null
    }
    // <t:UNIX[:STYLE]>
    if (inner.startsWith("t:")) {
        val body = inner.substring(2)
        val (unixStr, styleChar) = if (':' in body) {
            val colonAt = body.indexOf(':')
            body.substring(0, colonAt) to body.substring(colonAt + 1)
        } else {
            body to "f"
        }
        val unix = unixStr.toLongOrNull() ?: return null
        if (unix < 0) return null
        val style = timestampStyleOf(styleChar) ?: return null
        return RichTextInline.Timestamp(
            instant = kotlinx.datetime.Instant.fromEpochSeconds(unix),
            style = style,
        ) to consumed
    }
    return null
}

private fun timestampStyleOf(s: String): dev.puklic.domain.TimestampStyle? =
    when (s) {
        "t" -> dev.puklic.domain.TimestampStyle.SHORT_TIME
        "T" -> dev.puklic.domain.TimestampStyle.LONG_TIME
        "d" -> dev.puklic.domain.TimestampStyle.SHORT_DATE
        "D" -> dev.puklic.domain.TimestampStyle.LONG_DATE
        "f" -> dev.puklic.domain.TimestampStyle.SHORT_DATETIME
        "F" -> dev.puklic.domain.TimestampStyle.LONG_DATETIME
        "R" -> dev.puklic.domain.TimestampStyle.RELATIVE
        else -> null
    }

// Emoji detection — heuristic, multi-platform safe (no java.lang.Character) ───

private fun String.codePointAtOrNull(index: Int): Int? {
    if (index !in indices) return null
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return 0x10000 + (high.code - 0xD800) * 0x400 + (low.code - 0xDC00)
        }
    }
    return high.code
}

/**
 * True if the codepoint is likely the start of an emoji sequence.
 * Covers the common Discord-relevant ranges:
 *  - Misc Symbols & Pictographs (U+1F300–1F5FF)
 *  - Emoticons (U+1F600–1F64F)
 *  - Transport & Map (U+1F680–1F6FF)
 *  - Supplemental Symbols & Pictographs (U+1F900–1F9FF)
 *  - Symbols and Pictographs Extended-A (U+1FA70–1FAFF)
 *  - Misc Symbols (U+2600–26FF)
 *  - Dingbats (U+2700–27BF)
 *  - Regional indicators (U+1F1E6–1F1FF) — for flags
 */
private fun isLikelyEmojiStart(cp: Int): Boolean =
    (cp in 0x1F300..0x1F5FF) ||
        (cp in 0x1F600..0x1F64F) ||
        (cp in 0x1F680..0x1F6FF) ||
        (cp in 0x1F900..0x1F9FF) ||
        (cp in 0x1FA70..0x1FAFF) ||
        (cp in 0x2600..0x26FF) ||
        (cp in 0x2700..0x27BF) ||
        (cp in 0x1F1E6..0x1F1FF)

/**
 * Extends the emoji at [start] over any ZWJ-joined codepoints, variation selectors,
 * and skin-tone modifiers — returning the exclusive end index.
 */
private fun emojiSequenceEnd(input: String, start: Int): Int {
    var i = start
    val n = input.length
    // Always consume the first codepoint
    i += codepointLengthAt(input, i)
    while (i < n) {
        val ch = input[i]
        // Variation selector VS16 (U+FE0F)
        if (ch.code == 0xFE0F) {
            i++
            continue
        }
        // ZWJ U+200D — join with next codepoint
        if (ch.code == 0x200D) {
            val next = i + 1
            if (next >= n) break
            i = next + codepointLengthAt(input, next)
            continue
        }
        // Skin tone modifier (U+1F3FB..U+1F3FF) — high surrogate D83C + low surrogate
        val cp = input.codePointAtOrNull(i) ?: break
        if (cp in 0x1F3FB..0x1F3FF) {
            i += codepointLengthAt(input, i)
            continue
        }
        break
    }
    return i
}

private fun codepointLengthAt(input: String, index: Int): Int {
    if (index >= input.length) return 0
    val ch = input[index]
    return if (ch.isHighSurrogate() && index + 1 < input.length && input[index + 1].isLowSurrogate()) 2 else 1
}
