# ADR-0003: Cache & RAM strategie

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Hlavní hodnotová propozice Puklic je nízká spotřeba RAM (target < 150 MB idle, < 300 MB aktivní). Discord Electron drží stovky MB messages a attachments v paměti. Musíme definovat striktní caching pravidla, aby se tomu Puklic vyhnul.

## Decision

Vícevrstvá cache s bounded RAM a disk-backed perzistencí.

### Messages

| Vrstva | Obsah | Limit |
|---|---|---|
| RAM hot (aktivní channel) | ring buffer posledních N zpráv | **200** zpráv |
| RAM warm (recent channels) | posledních M zpráv per channel | **50** zpráv × max **5** channels |
| SQLite | kompletní historie viděná klientem | unbounded (disk) |
| Discord API | starší než SQLite cache | lazy fetch on scroll |

Při přepnutí kanálu se předchozí „hot" promítá do „warm". Šestý warm channel vypadne (LRU). UI scrolluje zpětně → `MessageRepository.loadOlder(channelId, beforeId)` → SQLite → API fallback.

### Attachments (obrázky, soubory, custom emoji, stickers)

- **Nikdy v RAM mimo aktuálně zobrazený frame.**
- Disk LRU cache v `$XDG_CACHE_HOME/puklic/attachments/` (default 500 MB, konfigurovatelné)
- Image loader: `Coil` (Compose Multiplatform port) s disk-backed cache
- Custom emoji + stickers: separátní cache `$XDG_CACHE_HOME/puklic/emoji/`, lazy decode při prvním zobrazení v daném kanálu, soft reference v RAM během sezení

### Guilds, channels, users (metadata)

- Drženo celé v RAM — je to malé (jednotky MB i pro 100+ guilds)
- Mirror v SQLite pro offline start
- Updates přes gateway events, ne polling

### Voice state, presence, typing

- Pouze RAM, ephemeral
- Žádná persistence — po restartu se naplní z gateway READY eventu

## Consequences

- ✅ RAM footprint deterministický, nezávislý na velikosti účtu (100 guilds × 1000 channels neznamená 100 000 zpráv v RAM)
- ✅ Cold start rychlý — UI se otevře proti SQLite, gateway se připojí asynchronně
- ⚠️ Scroll zpět může mít vizibilní latency (SQLite → API fallback) — UI musí mít skeleton/spinner
- ⚠️ Search napříč historií = SQLite full-text index, ne RAM scan
- 🔒 Žádná struktura nesmí držet `List<ChatMessage>` neomezeně. Repository vrací `Flow<List<ChatMessage>>` nad bounded oknem.
- 🔒 Image bitmapy nesmí žít v `MutableState` přes recompositions — vždy přes Coil image loader handle.

## Implementation hints (informativní, ne závazné)

- `MessageRepository` per channel scope, zaniká s ViewModelem channel view
- Ring buffer: `ArrayDeque<ChatMessage>` s `removeFirst()` při overflow
- LRU pro warm channels: `LinkedHashMap` s `accessOrder=true`
- SQLDelight queries: vždy s `LIMIT` a `ORDER BY timestamp DESC`

## Related

- ADR-0004: Coroutine-first state management
- `docs/03_infrastructure/cache-policy.md` (TBD) — konkrétní limity, konfigurace
- `docs/03_infrastructure/persistence-schema.md` (TBD)
