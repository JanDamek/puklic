# RichText AST + parser pipeline

Chat message content **není** `String` v UI. Discord posílá raw markdown-flavored text s vlastními extensions (mentions, custom emoji, channels). UI musí dostat **hotový AST**, který jen renderuje.

## AST model

Bydlí v `:shared:domain` (typy) a `:shared:chat-parser` (parser).

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

### Pravidla AST

- `Text.styles` = množina (lze kombinovat bold+italic), ne hierarchie
- Mentions, emoji, timestamps nesou **jen ID/ref**, ne resolved name — resolve dělá renderer s repository lookupem
- `Spoiler` může obsahovat libovolné inline elements
- `Link.display` může být plain text i další rich content (Discord embed-style link)
- `CodeBlock.content` zachovává whitespace a newlines 1:1

## Parser pipeline

```
raw String (z DiscordMessageDto.content)
   │
   ▼ 1. Lexer — tokenize markdown + Discord extensions
TokenStream
   │
   ▼ 2. Block parser — paragraphs, code blocks, quotes, lists
List<RawBlock>
   │
   ▼ 3. Inline parser — text styles, links, inline code per block
List<RichTextBlock> (s ref-only mentions/emoji)
   │
   ▼ 4. Reference resolution — žádný IO; jen syntax → typed refs
   ▼ (User/Role/Channel name resolution = lazy v rendereru přes Repository)
RichTextDocument (final)
```

Bydlí v `:shared:chat-parser`.

### Discord markdown subset (fáze 1)

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
| `:smile:` (unicode shortcode) | Emoji(Unicode) — pokud znám mapping |
| `<t:UNIX:STYLE>` | Timestamp |
| Unicode emoji codepoints | Emoji(Unicode) |

### Out of scope MVP

- Nested lists hlubší než 1 úroveň
- Tables (Discord nepodporuje)
- Math (`$$`) — Discord nepodporuje
- Custom HTML — nepovoleno

## Parser implementace — guidelines

- **Bez třetích parserů** (CommonMark, flexmark) — Discord markdown je subset s odlišnostmi (nested ** vs * priority, `||spoiler||`), vlastní parser je menší a přesnější
- **Pure function:** `fun parseRichText(raw: String): RichTextDocument` — žádné IO, žádný state, plně testovatelné
- **Tolerance to malformed input:** nikdy nehodit exception, neuzavřený `**bold` → text run s prefixem `**`
- **Performance:** parser běží na `Dispatchers.Default` při příchodu zprávy z gateway, výsledek se cachuje v `ChatMessage.parsedContent`. Re-parse jen při editu.
- **Memory:** AST stromy malé (≤ 100 nodes per typická zpráva). Žádný interning, prostý alokační overhead je OK.

## Renderer (Compose)

V `:desktop:compose-ui` (později i `:shared:compose-ui` pokud bude).

```kotlin
@Composable
fun RichTextView(
    document: RichTextDocument,
    mentionResolver: MentionResolver,    // pro resolved jméno/avatar
    emojiResolver: EmojiResolver,
    onLinkClick: (String) -> Unit,
)
```

**Pravidla rendereru:**
- UI **nikdy** neparsuje `String`. Vstup je vždy `RichTextDocument`.
- Resolvers jsou injektované (přes CompositionLocal nebo parametr), Repository-backed
- Resolvers vrací `State<ResolvedXxx>` (Flow → collectAsState) — během loadingu se zobrazí raw ID placeholder
- Code blocks: monospace font, později syntax highlighting (fáze 2)
- Spoilers: blackout overlay, klik odhalí (state per-spoiler v `remember`)

## Resolvers

```kotlin
interface MentionResolver {
    fun resolveUser(id: UserId): Flow<UserSummary?>
    fun resolveRole(id: RoleId): Flow<Role?>
    fun resolveChannel(id: ChannelId): Flow<Channel?>
}

interface EmojiResolver {
    fun resolveCustom(id: EmojiId): Flow<CustomEmojiInfo?>   // CDN URL, animated
    fun resolveUnicode(codepoint: String): UnicodeEmojiInfo? // sync, ze statické tabulky
}
```

Bydlí v `:shared:repositories`, implementuje proti Repository + cache.

## Test strategie

Parser tests:
- Golden-file based — `parser-fixtures/*.input.txt` + `*.expected.json` (serializovaný AST)
- Edge cases: nested markdown, malformed mentions, oversized input (Discord max 4000 znaků)
- Property-based (Kotest property): `parse(render(parse(x))) == parse(x)` (idempotence)

Renderer tests:
- Compose UI tests s mock resolvers
- Screenshot tests (až bude infra)
