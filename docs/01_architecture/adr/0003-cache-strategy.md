# ADR-0003: Cache & RAM strategy

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic's main value proposition is low RAM usage (target < 150 MB idle, < 300 MB active). Discord Electron holds hundreds of MB of messages and attachments in memory. We need to define strict caching rules so that Puklic avoids the same fate.

## Decision

A multi-layer cache with bounded RAM and disk-backed persistence.

### Messages

| Layer | Contents | Limit |
|---|---|---|
| RAM hot (active channel) | ring buffer of the last N messages | **200** messages |
| RAM warm (recent channels) | last M messages per channel | **50** messages × max **5** channels |
| SQLite | complete history seen by the client | unbounded (disk) |
| Discord API | older than the SQLite cache | lazy fetch on scroll |

When switching channels the previous "hot" is promoted to "warm". The sixth warm channel is evicted (LRU). UI scrolls back → `MessageRepository.loadOlder(channelId, beforeId)` → SQLite → API fallback.

### Attachments (images, files, custom emoji, stickers)

- **Never in RAM beyond the currently displayed frame.**
- Disk LRU cache in `$XDG_CACHE_HOME/puklic/attachments/` (default 500 MB, configurable)
- Image loader: `Coil` (Compose Multiplatform port) with disk-backed cache
- Custom emoji + stickers: separate cache `$XDG_CACHE_HOME/puklic/emoji/`, lazy decode on first display in a given channel, soft reference in RAM during the session

### Guilds, channels, users (metadata)

- Held entirely in RAM — it is small (single-digit MB even for 100+ guilds)
- Mirrored in SQLite for offline start
- Updates via gateway events, not polling

### Voice state, presence, typing

- RAM only, ephemeral
- No persistence — repopulated from the gateway READY event after restart

## Consequences

- ✅ RAM footprint is deterministic, independent of account size (100 guilds × 1000 channels does not mean 100 000 messages in RAM)
- ✅ Cold start is fast — UI opens against SQLite, gateway connects asynchronously
- ⚠️ Scrolling back may have visible latency (SQLite → API fallback) — the UI must have a skeleton/spinner
- ⚠️ Search across history = SQLite full-text index, not a RAM scan
- 🔒 No data structure may hold `List<ChatMessage>` unboundedly. Repository returns `Flow<List<ChatMessage>>` over a bounded window.
- 🔒 Image bitmaps must not live in `MutableState` across recompositions — always via the Coil image loader handle.

## Implementation hints (informative, not binding)

- `MessageRepository` per channel scope, disposed with the channel view's ViewModel
- Ring buffer: `ArrayDeque<ChatMessage>` with `removeFirst()` on overflow
- LRU for warm channels: `LinkedHashMap` with `accessOrder=true`
- SQLDelight queries: always with `LIMIT` and `ORDER BY timestamp DESC`

## Related

- ADR-0004: Coroutine-first state management
- `docs/03_infrastructure/cache-policy.md` (TBD) — concrete limits, configuration
- `docs/03_infrastructure/persistence-schema.md` (TBD)
