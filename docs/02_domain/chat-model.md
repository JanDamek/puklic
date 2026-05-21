# Chat doménový model

Tento dokument definuje **doménové typy** používané napříč `:shared:*` moduly. Tyto typy jsou **nezávislé** na Discord DTO (`:shared:protocol-discord`) i na perzistenci (`:shared:persistence-api`). Mapping mezi vrstvami je explicitní.

## Identifikátory

Všechny ID jsou type-safe value classes (inline classes), ne raw `String`/`Long`. Bydlí v `:shared:ids`.

```kotlin
@JvmInline value class UserId(val value: Long)
@JvmInline value class GuildId(val value: Long)
@JvmInline value class ChannelId(val value: Long)
@JvmInline value class MessageId(val value: Long)
@JvmInline value class RoleId(val value: Long)
@JvmInline value class EmojiId(val value: Long)
@JvmInline value class AttachmentId(val value: Long)
```

Důvod: kompilátor odmítne záměnu `ChannelId` za `MessageId`. Discord snowflake je 64-bit unsigned, ale Kotlin Long stačí (kladný rozsah pokryje vše do roku 2084).

## Top-level types

### `ChatMessage`

```kotlin
data class ChatMessage(
    val id: MessageId,
    val channelId: ChannelId,
    val author: UserSummary,
    val rawContent: String,                  // raw transport, primární pro edit / re-parse
    val parsedContent: RichTextDocument,     // hotový AST pro renderer
    val attachments: List<Attachment>,
    val embeds: List<MessageEmbed>,
    val reactions: List<Reaction>,
    val mentions: MessageMentions,
    val flags: MessageFlags,                 // pinned, tts, suppress_embeds, ephemeral, ...
    val timestamp: Instant,
    val editedTimestamp: Instant?,
    val referencedMessage: MessageReference?, // reply target
)
```

Pravidla:
- `rawContent` = single source of truth pro obsah. `parsedContent` se regeneruje při změně.
- `parsedContent` je výsledek `:shared:chat-parser` (viz [richtext-ast.md](richtext-ast.md)).
- `attachments`, `embeds`, `reactions` jsou **immutable** kopie. Update zprávy = nový `ChatMessage`.

### `UserSummary`

```kotlin
data class UserSummary(
    val id: UserId,
    val username: String,
    val globalName: String?,         // Discord display name (post pomelo)
    val discriminator: String?,      // legacy "0001", null pro pomelo accounts
    val avatarHash: String?,         // pro CDN URL composition
    val bot: Boolean,
    val system: Boolean,
)
```

V kontextu guildu může být obohacen na `GuildMember` (nickname, roles, joined_at). `UserSummary` je minimální subset pro autorství zprávy.

### `Guild`

```kotlin
data class Guild(
    val id: GuildId,
    val name: String,
    val iconHash: String?,
    val ownerId: UserId,
    val features: Set<GuildFeature>, // COMMUNITY, VERIFIED, ...
    val memberCount: Int?,
)
```

### `Channel`

```kotlin
sealed interface Channel {
    val id: ChannelId
    val name: String?
    val type: ChannelType
}

enum class ChannelType { GUILD_TEXT, DM, GUILD_VOICE, GROUP_DM, GUILD_CATEGORY, GUILD_ANNOUNCEMENT, ANNOUNCEMENT_THREAD, PUBLIC_THREAD, PRIVATE_THREAD, GUILD_STAGE_VOICE, GUILD_DIRECTORY, GUILD_FORUM, GUILD_MEDIA }

data class GuildTextChannel(
    override val id: ChannelId,
    override val name: String?,
    val guildId: GuildId,
    val parentId: ChannelId?,        // category
    val topic: String?,
    val position: Int,
    val rateLimitPerUser: Int,
    val nsfw: Boolean,
) : Channel { override val type = ChannelType.GUILD_TEXT }

data class DmChannel(
    override val id: ChannelId,
    val recipients: List<UserSummary>,
) : Channel {
    override val type = ChannelType.DM
    override val name: String? = null
}

// ... ostatní typy přidat dle potřeby ve fázi 1/2
```

Sealed hierarchy → exhaustive `when` v UI / repository, žádné runtime castingy.

### `Attachment`

```kotlin
data class Attachment(
    val id: AttachmentId,
    val filename: String,
    val size: Long,                  // bytes
    val url: String,                 // Discord CDN URL
    val proxyUrl: String,
    val contentType: String?,
    val width: Int?,                 // image / video
    val height: Int?,
    val durationSecs: Float?,        // voice message
    val description: String?,        // alt text
)
```

### `MessageEmbed`

```kotlin
data class MessageEmbed(
    val title: String?,
    val type: String?,               // "rich", "image", "video", "link", ...
    val description: String?,
    val url: String?,
    val timestamp: Instant?,
    val color: Int?,
    val footer: EmbedFooter?,
    val image: EmbedMedia?,
    val thumbnail: EmbedMedia?,
    val video: EmbedMedia?,
    val provider: EmbedProvider?,
    val author: EmbedAuthor?,
    val fields: List<EmbedField>,
)
```

Detail sub-typů viz Discord API docs — držet 1:1 shape pro snadný mapping.

### `Reaction`

```kotlin
data class Reaction(
    val emoji: EmojiRef,
    val count: Int,
    val me: Boolean,                 // přihlášený user reagoval
    val countDetails: ReactionCountDetails?, // burst vs normal
)

sealed interface EmojiRef {
    data class Unicode(val codepoint: String) : EmojiRef
    data class Custom(val id: EmojiId, val name: String, val animated: Boolean) : EmojiRef
}
```

### `MessageMentions`

```kotlin
data class MessageMentions(
    val users: List<UserId>,
    val roles: List<RoleId>,
    val channels: List<ChannelId>,
    val everyone: Boolean,
)
```

Resolved entities (`UserSummary`, `Channel.name`, ...) drží repository, ne `ChatMessage` přímo — vyhneme se duplikaci.

### `MessageReference`

```kotlin
data class MessageReference(
    val messageId: MessageId?,
    val channelId: ChannelId?,
    val guildId: GuildId?,
    val type: ReferenceType,         // REPLY, FORWARD
)
```

## Mapping vrstev

```
:shared:protocol-discord
  └─ DiscordMessageDto (JSON shape, snake_case, nullable everywhere)
       │ MessageMapper.toDomain()
       ▼
:shared:domain
  └─ ChatMessage (this file)
       │ MessageRepository.persist()  → MessageEntity (SQLDelight)
       │ MessageRepository.observe()  → Flow<List<ChatMessage>>
       ▼
:shared:repositories
       │ MessageListViewModel (StateFlow<MessageListState>)
       ▼
:desktop:compose-ui
  └─ MessageListView (@Composable)
```

**Mapping pravidla:**
- Mapper functions = `fun DiscordMessageDto.toDomain(): ChatMessage` extensions
- Null handling explicit při mappingu (Discord posílá hodně optional fieldů, doména je strict)
- Mapping nikdy v UI ani v Compose
- Persistence entity (`MessageEntity`) je vlastní typ s primitivy pro SQLite — ne `ChatMessage` přímo (rich content se serializuje samostatně)

## Equality & identity

- Všechny `data class` mají strukturální equality (Kotlin default)
- Pro caching/diff používat `MessageId` jako klíč (referenční equality v `LazyColumn` keys)
- `Instant` srovnáváme přes `==` (data class generuje), ne `compareTo` (kromě řazení)

## Lokální-only typy

Některá data v Puklic existují jen lokálně, ne na Discord:
- `MessageDeliveryState` — `Sending` / `Sent` / `Failed(retry: Int)` pro outbound queue
- `LocalDraft` — rozepsaná zpráva per channel, persistovaná do SQLite
- `ChannelReadState` — last-read message ID per channel (Discord má serverový read state, ale držíme lokální shadow pro offline)

Tyto typy nebydlí v `ChatMessage` — jsou v separátních modelech (`OutboundMessage`, `ChannelDraft`, `ReadState`).

## Versioning

Doménový model může breaking changovat — není veřejné API. Změna doménového typu = update mapperů + persistence schema (s migrací) + UI bindings v jednom commitu. Viz [persistence-schema.md](../03_infrastructure/persistence-schema.md) sekce „Migrations".
