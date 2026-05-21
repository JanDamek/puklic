# Cache policy

Konkretizace ADR-0003 — limity, eviction, konfigurace.

## RAM cache (in-process)

### Message hot cache

- **Owner:** `MessageRepository` per channel (ne globální)
- **Struktura:** `ArrayDeque<ChatMessage>` ring buffer
- **Limit:** 200 zpráv per aktivní channel
- **Eviction:** FIFO (oldest by timestamp)
- **Lifecycle:** vzniká při otevření channelu, předává se do `MessageListViewModel`

### Message warm cache (recent channels)

- **Owner:** `SessionCache` (per Discord session)
- **Struktura:** `LinkedHashMap<ChannelId, ArrayDeque<ChatMessage>>` (access-order LRU)
- **Limit:** 5 channels × 50 zpráv
- **Eviction:** 6. channel push → vypadne LRU; při flush channelu na disk se uvolní
- **Purpose:** rychlý switch back na nedávný channel bez SQLite read

### Guild / Channel / User metadata

- **Owner:** Per-typ Repository
- **Struktura:** `MutableStateFlow<Map<XxxId, Xxx>>`
- **Limit:** unbounded v RAM (malá data, jednotky MB i pro velký účet)
- **Eviction:** žádná během sezení; reset při logout
- **Persistence:** mirror v SQLite, hydratace při startu

### Custom emoji

- **Owner:** `EmojiRepository`
- **Struktura RAM:** `LruCache<EmojiId, EmojiDecoded>` (decoded bitmapy)
- **Limit:** 256 entries (≈ 8 MB RAM při 32×32 px)
- **Eviction:** LRU
- **Persistence:** raw bytes na disku v `$XDG_CACHE_HOME/puklic/emoji/`

### Image decode cache (Coil)

- **Owner:** Coil `ImageLoader` (singleton per app)
- **Limit RAM:** 25 % heap, ne víc než 64 MB
- **Limit disk:** 200 MB v `$XDG_CACHE_HOME/puklic/images/`
- **Eviction:** Coil LRU
- **Decode:** lazy on first display, recycled na recompose

## Disk cache

| Cache | Path | Default limit | Config key |
|---|---|---|---|
| Attachments | `$XDG_CACHE_HOME/puklic/attachments/` | 500 MB | `cache.attachments.maxBytes` |
| Images / thumbnails | `$XDG_CACHE_HOME/puklic/images/` | 200 MB | `cache.images.maxBytes` |
| Custom emoji | `$XDG_CACHE_HOME/puklic/emoji/` | 50 MB | `cache.emoji.maxBytes` |
| Stickers | `$XDG_CACHE_HOME/puklic/stickers/` | 100 MB | `cache.stickers.maxBytes` |
| **Total disk cache** | `$XDG_CACHE_HOME/puklic/` | **850 MB** | |

Plus SQLite DB v `$XDG_DATA_HOME/puklic/db/` (separate path, ne pod cache — neměl by se mazat při `rm -rf ~/.cache`).

### Disk eviction

- Background coroutine při startu sezení (`Dispatchers.IO`):
  1. Spočítá velikost každé cache
  2. Pokud > limit → `DELETE FROM attachment_cache_index ORDER BY last_accessed_at LIMIT N` + smazat fyzické soubory
- Re-check každých 30 minut během běhu
- Pri každém přístupu k attachmentu: `UPDATE attachment_cache_index SET last_accessed_at = ?` (batched, ne na každý read)

### Cache wipe

Manuální „Reset local cache" v Settings:
- 1. Confirm dialog
- 2. Smaže celý `$XDG_CACHE_HOME/puklic/`
- 3. Volitelně i `$XDG_DATA_HOME/puklic/db/` (separate checkbox „Wipe DB too")
- 4. Restart app

## Konfigurace

User-facing nastavení (Settings → Storage):

| Klíč | UI label | Range | Default |
|---|---|---|---|
| `cache.attachments.maxBytes` | „Velikost cache souborů" | 100 MB – 5 GB | 500 MB |
| `cache.images.maxBytes` | „Velikost cache obrázků" | 50 MB – 1 GB | 200 MB |
| `cache.disable` | „Vypnout disk cache" | bool | false |
| `messages.hotCacheSize` | (advanced, hidden) | 50–500 | 200 |
| `messages.warmCacheChannels` | (advanced, hidden) | 1–20 | 5 |

Persisted v `$XDG_CONFIG_HOME/puklic/settings.toml`.

## Invariants

- **RAM:** žádný `List<ChatMessage>` neomezené velikosti. Vždy bounded ring nebo Flow nad bounded oknem.
- **RAM:** žádné `ByteArray` attachmentu žije déle než jeden Compose frame.
- **Disk:** žádný soubor neexistuje bez záznamu v `attachment_cache_index` (orphan detection na startu).
- **Disk:** každý cache file má kontrolu velikosti (anti-disk-full).

## Test strategie

- **Memory leak test:** 24 h běh klienta, scroll 1000+ zpráv napříč 20 kanály. Heap growth target < 50 MB.
- **Cache eviction test:** naplnit attachment cache nad limit, ověřit že LRU evict funguje a velikost klesne pod limit.
- **Orphan test:** ručně přidat soubor do cache adresáře, ověřit, že startup ho smaže.
