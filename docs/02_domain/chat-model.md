# Chat domain model

This document defines the **domain types** used across `:shared:*` modules. These types are **independent** of Discord DTOs (`:shared:protocol-discord`) and of persistence (`:shared:persistence-api`). Mapping between layers is explicit.

## Identifiers

All IDs are type-safe value classes (inline classes), not raw `String`/`Long`. They live in `:shared:ids`.

```kotlin
@JvmInline value class UserId(val value: Long)
@JvmInline value class GuildId(val value: Long)
@JvmInline value class ChannelId(val value: Long)
@JvmInline value class MessageId(val value: Long)
@JvmInline value class RoleId(val value: Long)
@JvmInline value class EmojiId(val value: Long)
@JvmInline value class AttachmentId(val value: Long)
```

Rationale: the compiler rejects mixing up `ChannelId` with `MessageId`. Discord snowflakes are 64-bit unsigned, but Kotlin Long is sufficient (the positive range covers everything until 2084).

## Top-level types

### `ChatMessage`

```kotlin
data class ChatMessage(
    val id: MessageId,
    val channelId: ChannelId,
    val author: UserSummary,
    val rawContent: String,                  // raw transport, primary for edit / re-parse
    val parsedContent: RichTextDocument,     // finished AST for the renderer
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

Rules:
- `rawContent` = single source of truth for content. `parsedContent` is regenerated on change.
- `parsedContent` is the result of `:shared:chat-parser` (see [richtext-ast.md](richtext-ast.md)).
- `attachments`, `embeds`, `reactions` are **immutable** copies. Updating a message = new `ChatMessage`.

### `UserSummary`

```kotlin
data class UserSummary(
    val id: UserId,
    val username: String,
    val globalName: String?,         // Discord display name (post-pomelo)
    val discriminator: String?,      // legacy "0001", null for pomelo accounts
    val avatarHash: String?,         // for CDN URL composition
    val bot: Boolean,
    val system: Boolean,
)
```

In a guild context it can be enriched to `GuildMember` (nickname, roles, joined_at). `UserSummary` is the minimal subset for message authorship.

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

// ... add other types as needed in Phase 1/2
```

Sealed hierarchy → exhaustive `when` in UI / repository, no runtime casts.

#### DM lifecycle (issue #17)

A DM channel may already exist (delivered in `READY.private_channels` or via `CHANNEL_CREATE`)
or be created on-demand by the user picking a recipient in the "Start new DM" picker. The
picker sources candidates from the local cache only:

1. Existing DM recipients (in-memory via `DmListOrchestrator.dms`)
2. Persisted cached users (`UserRepository.searchByName` — populated by `READY.users`,
   message authors, mentions, observed guild members)

Self user, bots and system users are filtered out (manual DMs are user-to-user). Discord ToS
forbids server-side user-directory enumeration; the picker therefore never queries any
"search users" endpoint — only the local cache. See `NewDmSearch` in `:shared:repositories`.

DM creation goes through `DiscordSessionBridge.createOrOpenDm` →
`POST /users/@me/channels` with body `{"recipients":["<userId>"]}`. The endpoint is
idempotent on Discord's side: calling it with a recipient that already has a DM channel
returns the existing channel (same id). No client-side dedup is needed.

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

Detail of sub-types see Discord API docs — keep 1:1 shape for easy mapping.

### `Reaction`

```kotlin
data class Reaction(
    val emoji: EmojiRef,
    val count: Int,
    val me: Boolean,                 // logged-in user reacted
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

Resolved entities (`UserSummary`, `Channel.name`, ...) are held by the repository, not `ChatMessage` directly — avoids duplication.

### `MessageReference`

```kotlin
data class MessageReference(
    val messageId: MessageId?,
    val channelId: ChannelId?,
    val guildId: GuildId?,
    val type: ReferenceType,         // REPLY, FORWARD
)
```

## Layer mapping

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

**Mapping rules:**
- Mapper functions = `fun DiscordMessageDto.toDomain(): ChatMessage` extensions
- Null handling explicit in the mapper (Discord sends many optional fields, domain is strict)
- Mapping never happens in UI or in Compose
- Persistence entity (`MessageEntity`) is its own type with primitives for SQLite — not `ChatMessage` directly (rich content is serialized separately)

## Equality & identity

- All `data class` instances have structural equality (Kotlin default)
- For caching/diffing use `MessageId` as the key (referential equality in `LazyColumn` keys)
- `Instant` compared via `==` (generated by data class), not `compareTo` (except for sorting)

## Local-only types

Some data in Puklic exists only locally, not on Discord:
- `MessageDeliveryState` — `Sending` / `Sent` / `Failed(retry: Int)` for the outbound queue
- `LocalDraft` — a message being composed per channel, persisted to SQLite
- `ChannelReadState` — last-read message ID per channel (Discord has server-side read state, but we keep a local shadow for offline)

These types do not live in `ChatMessage` — they are in separate models (`OutboundMessage`, `ChannelDraft`, `ReadState`).

## Versioning

The domain model may break-change — it is not a public API. Changing a domain type = update mappers + persistence schema (with migration) + UI bindings in one commit. See [persistence-schema.md](../03_infrastructure/persistence-schema.md) section "Migrations".
