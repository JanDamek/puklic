# Phase 2 — Reactions UI — Architect Report

## Summary

Reactions are already modeled in domain (`Reaction`, `EmojiRef`), mapped from REST DTOs (`MessageMapper.toDomain`), and persisted as `reactions_json` on the message row. Phase 2 work is three thin slices: (1) extend REST + `MessageGateway` with toggle endpoints, (2) add 4 reaction gateway events into the `DiscordGatewayBridge`/`MessageOrchestrator` pipeline as in-place message mutations, (3) add a `ReactionsRow` composable + minimal picker to `MessageRow` and wire `onReact` through the channel screen / view-model. No schema migration. No new persistence table.

## 1. Module touch map

**Modified**
- `shared/protocol-discord/.../rest/DiscordRestClient.kt` — add `addReaction` / `removeReaction`
- `shared/protocol-discord/.../PublicApi.kt` — add 4 reaction events to `DiscordDomainEvent`; map `MESSAGE_REACTION_*` in `mapDispatch`
- `shared/repositories/.../GatewayEventSource.kt` — mirror 4 events; bridge in wiring
- `shared/repositories/.../MessageGateway.kt` — `addReaction` / `removeReaction` suspend functions
- `shared/repositories/.../MessageOrchestrator.kt` — handle 4 reaction events (read-modify-write); add `toggleReaction(...)` for optimistic UI; inject `selfUserIdProvider`
- `shared/compose-ui/.../components/MessageRow.kt` — render `ReactionsRow` under body; surface `onReact(EmojiRef)`
- channel screen + view-model — wire `onReact`
- wiring module — implement new REST calls

**New**
- `shared/compose-ui/.../components/ReactionsRow.kt` — chip row + "+" button
- `shared/compose-ui/.../components/EmojiPickerDialog.kt` — minimal: unicode text field + grid of recent + guild custom emoji
- `shared/repositories/.../ReactionMutator.kt` (internal) — pure `add/remove/clearAll/clearEmoji` on `List<Reaction>`, unit-tested

## 2. Domain

Already complete. No additions.
```kotlin
sealed interface EmojiRef {
    data class Unicode(val codepoint: String) : EmojiRef
    data class Custom(val id: EmojiId, val name: String, val animated: Boolean) : EmojiRef
}
data class Reaction(val emoji: EmojiRef, val count: Int, val me: Boolean, val countDetails: ReactionCountDetails?)
```

## 3. Persistence

**Decision: keep `reactions_json` embedded on `message` row. No new table.**

Reactions are bounded (~20 distinct/message), always read with the message, low event rate. A separate table = migration + join + FK + extra Flow merge for zero gain. Read-modify-write through `selectById` + `persist` is enough.

## 4. DTO mapping

`MessageMapper.kt` already has `DiscordReactionDto.toDomain()` and `DiscordEmojiRefDto.toDomain()`. Gateway reaction events carry only a partial emoji + meta; decode inline in `mapDispatch` (4 fields — no new DTO class needed).

## 5. REST URL encoding

```
PUT    /channels/{cid}/messages/{mid}/reactions/{emoji}/@me
DELETE /channels/{cid}/messages/{mid}/reactions/{emoji}/@me
```

- Unicode: percent-encode raw UTF-8 (👍 → `%F0%9F%91%8D`) via `encodeURLPathPart`.
- Custom: `name:id` then percent-encode uniformly.

Helper in `DiscordRestClient`:
```kotlin
private fun EmojiRef.toReactionPath(): String = when (this) {
    is EmojiRef.Unicode -> codepoint.encodeURLPathPart()
    is EmojiRef.Custom  -> "${name}:${id.value}".encodeURLPathPart()
}
```
`EmojiRef` is domain, so passing it through `MessageGateway` does not violate the layering rule.

## 6. Gateway events

Add to `DiscordDomainEvent` and mirror in `GatewayDomainEvent`:
```kotlin
data class ReactionAdded(channelId, messageId, userId, emoji: EmojiRef, meBurst: Boolean = false)
data class ReactionRemoved(channelId, messageId, userId, emoji: EmojiRef)
data class ReactionsClearedAll(channelId, messageId)
data class ReactionsClearedEmoji(channelId, messageId, emoji: EmojiRef)
```

`MessageOrchestrator` extends its event `when` with read-modify-write through a private helper:
```kotlin
private suspend inline fun mutate(id: MessageId, transform: (ChatMessage) -> ChatMessage) {
    val current = storage.selectById(id) ?: return
    storage.persist(transform(current))
}
```
`storage.persist` triggers SQLDelight's `messagesByChannel` Flow → UI recomposes. No new Flow.

`selfUserId` injection: constructor `selfUserIdProvider: () -> UserId?` (no session-repo coupling).

`ReactionMutator` (pure, idempotent on self-echo):
- `add(list, emoji, isMe)`: bump count + set `me`; skip if `isMe && existing.me` (idempotent for optimistic echo).
- `remove(list, emoji, isMe)`: decrement; drop at 0; clear `me` only when `isMe`.
- `clearAll()` = `emptyList()`
- `clearEmoji(list, emoji)` = `filterNot`
- `emojiKey(emoji)` = `"u:codepoint"` / `"c:id"` to avoid collisions.

## 7. UI

`ReactionsRow` in `MessageRow`'s body column after embeds:
- `ReactionChip`: rounded 12dp Surface, ~24dp height, tinted background when `reaction.me == true`. Content: emoji (Unicode `Text` / Custom `AsyncImage` from `https://cdn.discordapp.com/emojis/{id}.{png|gif}`, 16dp) + count. Clickable → `onToggle(reaction.emoji)`.
- Trailing "+" chip → opens `EmojiPickerDialog`.

`EmojiPickerDialog` (simplest viable):
- `AlertDialog` with `OutlinedTextField` for raw unicode paste (commit → `EmojiRef.Unicode`).
- `LazyVerticalGrid` of recently used (in-memory `StateFlow` on screen VM for v1).
- Optional grid of guild custom emoji from existing `CustomEmoji` SQLDelight table.

Wire in channel screen:
```kotlin
onReact = { emoji ->
    vm.toggleReaction(message.id, message.channelId, emoji,
        alreadyReacted = message.reactions.any { it.me && it.emoji == emoji })
}
```

## 8. Optimistic updates

VM `toggleReaction`:
1. Compute mutated `ChatMessage` via `ReactionMutator` with `isMe = true`.
2. `storage.persist(mutated)` — UI updates immediately.
3. Call `messageGateway.addReaction / removeReaction`.
4. On REST failure: revert with inverse `ReactionMutator` and persist.
5. The gateway will echo our action; `add` is idempotent on `isMe && existing.me`. Place idempotency in `MessageOrchestrator` so optimistic + echo converge regardless of order.

## 9. Risk / open questions

1. **Self-bot perception.** Picker requires explicit user click. No bulk / auto-react ever (per `CLAUDE.md` "What Puklic IS NOT").
2. **Burst / super-reactions.** `count_details.burst` persisted but not rendered v1. `me_burst` from gateway ignored.
3. **Custom emoji disk cache.** Coordinate with "Custom emoji (CDN, disk cache)" task — share Coil cache config. Reactions can ship before the dedicated emoji-cache task; `AsyncImage` on the raw CDN URL works as fallback.
