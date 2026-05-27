# RichText AST + parser pipeline

Chat message content is **not** a `String` in the UI. Discord sends raw markdown-flavored text with its own extensions (mentions, custom emoji, channels). The UI must receive a **finished AST** that it only renders.

## AST model

Lives in `:shared:domain` (types) and `:shared:chat-parser` (parser).

```kotlin
data class RichTextDocument(
    val blocks: List<RichTextBlock>,
)

sealed interface RichTextBlock {
    data class Paragraph(val runs: List<RichTextInline>) : RichTextBlock
    data class CodeBlock(val language: String?, val content: String) : RichTextBlock
    data class Quote(val content: List<RichTextBlock>) : RichTextBlock
    data class Heading(val level: Int, val runs: List<RichTextInline>) : RichTextBlock  // # ## ###, level 1-3
    data class List(val ordered: Boolean, val items: List<ListItem>) : RichTextBlock
    data class ListItem(val content: List<RichTextBlock>)
}

sealed interface RichTextInline {
    data class Text(val content: String, val styles: Set<TextStyle>) : RichTextInline
    data class InlineCode(val content: String) : RichTextInline
    data class Link(val url: String, val display: List<RichTextInline>) : RichTextInline
    data class Mention(val target: MentionTarget) : RichTextInline
    data class Emoji(val ref: EmojiRef) : RichTextInline
    data class Spoiler(val content: List<RichTextInline>) : RichTextInline
    data class Timestamp(val instant: Instant, val style: TimestampStyle) : RichTextInline
    data class LineBreak(val soft: Boolean) : RichTextInline      // \n vs hard break
}

enum class TextStyle { BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }
enum class TimestampStyle { SHORT_TIME, LONG_TIME, SHORT_DATE, LONG_DATE, SHORT_DATETIME, LONG_DATETIME, RELATIVE }

sealed interface MentionTarget {
    data class User(val id: UserId) : MentionTarget
    data class Role(val id: RoleId) : MentionTarget
    data class Channel(val id: ChannelId) : MentionTarget
    data object Everyone : MentionTarget
    data object Here : MentionTarget
}
```

### AST rules

- `Text.styles` = a set (can combine bold+italic), not a hierarchy
- Mentions, emoji, timestamps carry **only the ID/ref**, not the resolved name — resolution is done by the renderer with a repository lookup
- `Spoiler` may contain arbitrary inline elements
- `Link.display` can be plain text or further rich content (Discord embed-style link)
- `CodeBlock.content` preserves whitespace and newlines 1:1

## Parser pipeline

```
raw String (from DiscordMessageDto.content)
   │
   ▼ 1. Lexer — tokenize markdown + Discord extensions
TokenStream
   │
   ▼ 2. Block parser — paragraphs, code blocks, quotes, lists
List<RawBlock>
   │
   ▼ 3. Inline parser — text styles, links, inline code per block
List<RichTextBlock> (with ref-only mentions/emoji)
   │
   ▼ 4. Reference resolution — no IO; syntax → typed refs only
   ▼ (User/Role/Channel name resolution = lazy in renderer via Repository)
RichTextDocument (final)
```

Lives in `:shared:chat-parser`.

### Discord markdown subset (Phase 1)

| Syntax | Element |
|---|---|
| `**bold**` | Text(BOLD) |
| `*italic*` / `_italic_` | Text(ITALIC) |
| `__underline__` | Text(UNDERLINE) |
| `~~strike~~` | Text(STRIKETHROUGH) |
| `` `code` `` | InlineCode |
| ` ```lang\ncode\n``` ` | CodeBlock |
| `> quote` | Quote |
| `# heading` | Heading(1) |
| `||spoiler||` | Spoiler |
| `[label](url)` | Link |
| autolink `https://...` | Link (display = url) |

### Discord extensions

| Syntax | Element |
|---|---|
| `<@USER_ID>` | Mention(User) |
| `<@&ROLE_ID>` | Mention(Role) |
| `<#CHANNEL_ID>` | Mention(Channel) |
| `@everyone`, `@here` | Mention(Everyone/Here) |
| `<:name:ID>` | Emoji(Custom, animated=false) |
| `<a:name:ID>` | Emoji(Custom, animated=true) |
| `:smile:` (unicode shortcode) | Emoji(Unicode) — if the mapping is known |
| `<t:UNIX:STYLE>` | Timestamp |
| Unicode emoji codepoints | Emoji(Unicode) |

### Out of scope for MVP

- Nested lists deeper than 1 level
- Tables (Discord does not support them)
- Math (`$$`) — Discord does not support it
- Custom HTML — not allowed

## Parser implementation — guidelines

- **No third-party parsers** (CommonMark, flexmark) — Discord markdown is a subset with differences (nested ** vs * priority, `||spoiler||`); a custom parser is smaller and more accurate
- **Pure function:** `fun parseRichText(raw: String): RichTextDocument` — no IO, no state, fully testable
- **Tolerance of malformed input:** never throw an exception; an unclosed `**bold` → text run with `**` prefix
- **Performance:** parser runs on `Dispatchers.Default` when a message arrives from the gateway; result is cached in `ChatMessage.parsedContent`. Re-parse only on edit.
- **Memory:** AST trees are small (≤ 100 nodes per typical message). No interning; plain allocation overhead is acceptable.

## Renderer (Compose)

In `:desktop:compose-ui` (later also `:shared:compose-ui` when applicable).

```kotlin
@Composable
fun RichTextView(
    document: RichTextDocument,
    mentionResolver: MentionResolver,    // for resolved name/avatar
    emojiResolver: EmojiResolver,
    onLinkClick: (String) -> Unit,
)
```

**Renderer rules:**
- UI **never** parses a `String`. Input is always a `RichTextDocument`.
- Resolvers are injected (via CompositionLocal or parameter), backed by a Repository
- Resolvers return `State<ResolvedXxx>` (Flow → collectAsState) — while loading, the raw ID placeholder is shown
- Code blocks: monospace font, syntax highlighting later (Phase 2)
- Spoilers: blackout overlay, click reveals (state per spoiler in `remember`)

## Resolvers

```kotlin
interface MentionResolver {
    fun resolveUser(id: UserId): Flow<UserSummary?>
    fun resolveRole(id: RoleId): Flow<RoleDisplay?>
    fun resolveChannel(id: ChannelId): Flow<Channel?>
}

data class RoleDisplay(val name: String, val colorArgb: Int?)

interface EmojiResolver {
    fun resolveCustom(id: EmojiId): Flow<CustomEmojiInfo?>   // CDN URL, animated
    fun resolveUnicode(codepoint: String): UnicodeEmojiInfo? // sync, from static table
}
```

Lives in `:shared:compose-ui` (`dev.puklic.ui.resolvers`). The shipped implementation
`RepositoryMentionResolver` is backed by:

| Target | Source | Render thread cost |
|---|---|---|
| `<@user>` / `<@!user>` | `UserRepository.findById` (SQLite, cached) | suspending lookup once per id, cached recomposition thereafter |
| `<#channel>` | `ChannelRepository.findById` (SQLite, cached) | suspending lookup once per id |
| `<@&role>` | `RoleStore` (in-memory `StateFlow`) | reactive, no IO — re-emits on `GUILD_ROLE_*` events |
| `@everyone` / `@here` | n/a — rendered literally with mention chip style | none |

Unresolved mentions fall back to the literal placeholder produced by `renderMention()`
(`@user`, `@role`, `#channel`). The fallback is deterministic — there is no spinner and no
async retry on the render path, satisfying the "UI must not parse or transform data" rule
from CLAUDE.md.

**Role colour.** `RoleDisplay.colorArgb` is currently always `null`. The Discord DTO layer
(`shared/protocol-discord/.../dto/RoleDto.kt`) does not yet expose the `color` field, so
plumbing a coloured chip would require a DTO + mapper change owned by a different module.
The renderer is colour-ready — when the DTO gains the field, `RepositoryMentionResolver`
needs only to forward it (no UI change required).

## Test strategy

Parser tests:
- Golden-file based — `parser-fixtures/*.input.txt` + `*.expected.json` (serialized AST)
- Edge cases: nested markdown, malformed mentions, oversized input (Discord max 4000 characters)
- Property-based (Kotest property): `parse(render(parse(x))) == parse(x)` (idempotence)

Renderer tests:
- Compose UI tests with mock resolvers
- Screenshot tests (once infra is in place)
