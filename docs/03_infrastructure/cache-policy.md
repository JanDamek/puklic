# Cache policy

Specification of ADR-0003 — limits, eviction, configuration.

## RAM cache (in-process)

### Message hot cache

- **Owner:** `MessageRepository` per channel (not global)
- **Structure:** `ArrayDeque<ChatMessage>` ring buffer
- **Limit:** 200 messages per active channel
- **Eviction:** FIFO (oldest by timestamp)
- **Lifecycle:** created when a channel is opened, passed to `MessageListViewModel`

### Message warm cache (recent channels)

- **Owner:** `SessionCache` (per Discord session)
- **Structure:** `LinkedHashMap<ChannelId, ArrayDeque<ChatMessage>>` (access-order LRU)
- **Limit:** 5 channels × 50 messages
- **Eviction:** 6th channel push → LRU evicted; flushing a channel to disk also frees it
- **Purpose:** fast switch back to a recent channel without a SQLite read

### Guild / Channel / User metadata

- **Owner:** per-type Repository
- **Structure:** `MutableStateFlow<Map<XxxId, Xxx>>`
- **Limit:** unbounded in RAM (small data, single-digit MB even for a large account)
- **Eviction:** none during the session; reset on logout
- **Persistence:** mirrored in SQLite, hydrated on startup

### Custom emoji

- **Owner:** `EmojiRepository`
- **RAM structure:** `LruCache<EmojiId, EmojiDecoded>` (decoded bitmaps)
- **Limit:** 256 entries (≈ 8 MB RAM at 32×32 px)
- **Eviction:** LRU
- **Persistence:** raw bytes on disk in `$XDG_CACHE_HOME/puklic/emoji/`

### Image decode cache (Coil)

- **Owner:** Coil `ImageLoader` (singleton per app)
- **RAM limit:** 25 % of heap, no more than 64 MB
- **Disk limit:** 200 MB in `$XDG_CACHE_HOME/puklic/images/`
- **Eviction:** Coil LRU
- **Decode:** lazy on first display, recycled on recompose

## Disk cache

| Cache | Path | Default limit | Config key |
|---|---|---|---|
| Attachments | `$XDG_CACHE_HOME/puklic/attachments/` | 500 MB | `cache.attachments.maxBytes` |
| Images / thumbnails | `$XDG_CACHE_HOME/puklic/images/` | 200 MB | `cache.images.maxBytes` |
| Custom emoji | `$XDG_CACHE_HOME/puklic/emoji/` | 50 MB | `cache.emoji.maxBytes` |
| Stickers | `$XDG_CACHE_HOME/puklic/stickers/` | 100 MB | `cache.stickers.maxBytes` |
| **Total disk cache** | `$XDG_CACHE_HOME/puklic/` | **850 MB** | |

Plus SQLite DB in `$XDG_DATA_HOME/puklic/db/` (separate path, not under cache — should not be deleted by `rm -rf ~/.cache`).

### Disk eviction

- Background coroutine at session start (`Dispatchers.IO`):
  1. Calculates the size of each cache
  2. If > limit → `DELETE FROM attachment_cache_index ORDER BY last_accessed_at LIMIT N` + delete physical files
- Re-check every 30 minutes while running
- On each attachment access: `UPDATE attachment_cache_index SET last_accessed_at = ?` (batched, not on every read)

### Cache wipe

Manual "Reset local cache" in Settings:
- 1. Confirmation dialog
- 2. Delete the entire `$XDG_CACHE_HOME/puklic/`
- 3. Optionally also `$XDG_DATA_HOME/puklic/db/` (separate checkbox "Wipe DB too")
- 4. Restart app

## Configuration

User-facing settings (Settings → Storage):

| Key | UI label | Range | Default |
|---|---|---|---|
| `cache.attachments.maxBytes` | "File cache size" | 100 MB – 5 GB | 500 MB |
| `cache.images.maxBytes` | "Image cache size" | 50 MB – 1 GB | 200 MB |
| `cache.disable` | "Disable disk cache" | bool | false |
| `messages.hotCacheSize` | (advanced, hidden) | 50–500 | 200 |
| `messages.warmCacheChannels` | (advanced, hidden) | 1–20 | 5 |

Persisted in `$XDG_CONFIG_HOME/puklic/settings.toml`.

## Invariants

- **RAM:** no `List<ChatMessage>` of unbounded size. Always a bounded ring or Flow over a bounded window.
- **RAM:** no `ByteArray` attachment lives longer than one Compose frame.
- **Disk:** no file exists without a record in `attachment_cache_index` (orphan detection on startup).
- **Disk:** every cache file has a size check (anti-disk-full).

## Test strategy

- **Memory leak test:** 24 h client run, scroll 1000+ messages across 20 channels. Heap growth target < 50 MB.
- **Cache eviction test:** fill attachment cache above limit, verify LRU eviction works and size drops below limit.
- **Orphan test:** manually add a file to the cache directory, verify that startup deletes it.
