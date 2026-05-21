# Persistence schema (SQLDelight + SQLite)

Definice tabulek pro lokální cache. Implementace v `:shared:persistence-api` (interfaces) + `:desktop:persistence-sqldelight` (per-platform actual). Stejné schema platí na všech platformách (Desktop/Android/iOS) — SQLDelight generuje platform-specific drivery.

## Umístění databáze

- Linux: `$XDG_DATA_HOME/puklic/db/puklic.db` (default `~/.local/share/puklic/db/puklic.db`)
- macOS: `~/Library/Application Support/Puklic/db/puklic.db`
- Windows: `%APPDATA%/Puklic/db/puklic.db`
- Android: standardní context.getDatabasePath
- iOS: Application Support directory

Cesta poskytuje `PlatformPaths.databaseFile(): Path`, viz [platform-abstractions.md](platform-abstractions.md).

## Pragmas

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA temp_store = MEMORY;
PRAGMA mmap_size = 268435456;     -- 256 MB
PRAGMA cache_size = -8000;        -- 8 MB
```

WAL pro concurrent read během write. `cache_size` záporné = KB, ne pages.

## Tabulky

### `account`

Per-token entry. Klient může držet historii několika účtů (re-login).

```sql
CREATE TABLE account (
    user_id INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    global_name TEXT,
    discriminator TEXT,
    avatar_hash TEXT,
    last_login_at INTEGER NOT NULL  -- epoch ms
);
```

Token **není v DB** — drží se v platform secure storage, klíčovaný `user_id`.

### `guild`

```sql
CREATE TABLE guild (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    icon_hash TEXT,
    owner_id INTEGER NOT NULL,
    features TEXT NOT NULL,         -- JSON array of strings
    member_count INTEGER,
    updated_at INTEGER NOT NULL
);
```

### `channel`

```sql
CREATE TABLE channel (
    id INTEGER PRIMARY KEY,
    guild_id INTEGER,               -- NULL pro DM
    parent_id INTEGER,              -- category, FK channel(id)
    type INTEGER NOT NULL,          -- ChannelType ordinal
    name TEXT,
    topic TEXT,
    position INTEGER,
    rate_limit_per_user INTEGER DEFAULT 0,
    nsfw INTEGER DEFAULT 0,
    last_message_id INTEGER,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (guild_id) REFERENCES guild(id) ON DELETE CASCADE
);

CREATE INDEX channel_guild_idx ON channel(guild_id);
CREATE INDEX channel_parent_idx ON channel(parent_id);
```

### `user`

User cache pro author lookup, mentions resolution.

```sql
CREATE TABLE user (
    id INTEGER PRIMARY KEY,
    username TEXT NOT NULL,
    global_name TEXT,
    discriminator TEXT,
    avatar_hash TEXT,
    bot INTEGER DEFAULT 0,
    system INTEGER DEFAULT 0,
    updated_at INTEGER NOT NULL
);
```

### `message`

```sql
CREATE TABLE message (
    id INTEGER PRIMARY KEY,
    channel_id INTEGER NOT NULL,
    author_id INTEGER NOT NULL,
    raw_content TEXT NOT NULL,
    timestamp INTEGER NOT NULL,     -- epoch ms
    edited_timestamp INTEGER,
    flags INTEGER NOT NULL DEFAULT 0,
    reference_message_id INTEGER,   -- reply target
    reference_channel_id INTEGER,
    reference_type INTEGER,         -- 0 = reply, 1 = forward
    mentions_everyone INTEGER DEFAULT 0,
    -- Strukturované sub-objekty jako JSON pro jednoduchost:
    -- (alternativně samostatné tabulky až bude potřeba dotazovat)
    attachments_json TEXT NOT NULL DEFAULT '[]',
    embeds_json TEXT NOT NULL DEFAULT '[]',
    reactions_json TEXT NOT NULL DEFAULT '[]',
    mentions_users_json TEXT NOT NULL DEFAULT '[]',
    mentions_roles_json TEXT NOT NULL DEFAULT '[]',
    mentions_channels_json TEXT NOT NULL DEFAULT '[]',
    FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES user(id)
);

CREATE INDEX message_channel_timestamp_idx ON message(channel_id, timestamp DESC);
CREATE INDEX message_author_idx ON message(author_id);
```

**Pozn. k JSON columns:**
- Pro fáze 1 stačí JSON serializace sub-objektů
- Pokud bude potřeba dotazovat („všechny zprávy s reakcí emoji X") → migrace na normalizované tabulky `message_reaction`, `message_attachment`, atd. (řeší ADR později)

### `attachment_cache_index`

Metadata pro disk cache attachmentů. Soubory na disku, metadata v DB pro LRU eviction.

```sql
CREATE TABLE attachment_cache_index (
    attachment_id INTEGER PRIMARY KEY,
    url TEXT NOT NULL,
    local_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    content_type TEXT,
    cached_at INTEGER NOT NULL,
    last_accessed_at INTEGER NOT NULL
);

CREATE INDEX attachment_cache_lru_idx ON attachment_cache_index(last_accessed_at);
```

### `custom_emoji`

```sql
CREATE TABLE custom_emoji (
    id INTEGER PRIMARY KEY,
    guild_id INTEGER NOT NULL,
    name TEXT NOT NULL,
    animated INTEGER DEFAULT 0,
    available INTEGER DEFAULT 1,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (guild_id) REFERENCES guild(id) ON DELETE CASCADE
);

CREATE INDEX custom_emoji_guild_idx ON custom_emoji(guild_id);
CREATE INDEX custom_emoji_name_idx ON custom_emoji(name);
```

### `read_state`

```sql
CREATE TABLE read_state (
    channel_id INTEGER PRIMARY KEY,
    last_message_id INTEGER,
    mention_count INTEGER DEFAULT 0,
    last_viewed_at INTEGER,
    FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE
);
```

### `local_draft`

Rozepsaná zpráva per channel, neodeslaná.

```sql
CREATE TABLE local_draft (
    channel_id INTEGER PRIMARY KEY,
    content TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE
);
```

### `outbound_message`

Queue pro odesílaní (offline / retry).

```sql
CREATE TABLE outbound_message (
    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    attachments_json TEXT NOT NULL DEFAULT '[]',
    reference_message_id INTEGER,
    nonce TEXT NOT NULL,            -- pro server idempotence
    created_at INTEGER NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    state INTEGER NOT NULL DEFAULT 0,  -- 0=pending, 1=sending, 2=failed
    FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE
);

CREATE INDEX outbound_state_idx ON outbound_message(state, created_at);
```

### `schema_version`

```sql
CREATE TABLE schema_version (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);
```

SQLDelight má vlastní migration mechanism (`Migration.kt`), `schema_version` slouží jen pro debug / sanity check.

## Migrace

SQLDelight migrace = sekvence `.sqm` souborů (`migrations/1.sqm`, `2.sqm`, ...).

**Pravidla:**
- Nikdy needitovat již-shippnutou migraci
- Každá change = nový `.sqm` s `ALTER TABLE` / `CREATE INDEX` / data migration
- Při major schema changi → pokud nelze migrovat, **WIPE DB** je akceptovatelný (data jsou cache, autoritativní zdroj je Discord) — ale **musí být** opt-in dialog v UI, ne tiché smazání
- Cache wipe je samostatná funkce v Settings → „Reset local cache"

## Performance hints

- `INSERT OR REPLACE` pro upsert eventů (gateway `MESSAGE_UPDATE` jako full replace zprávy)
- Bulk insert (READY initial guild/channel state) v jedné transakci
- `SELECT` zpráv pro UI: vždy `LIMIT 200` + `ORDER BY timestamp DESC`
- Pagination přes `WHERE timestamp < ?` (nikoli OFFSET)

## Velikost DB

Odhad pro typický účet (50 guildů, 500 channels, 6 měsíců historie):
- Messages: ~50 000 zpráv × ~500 B = ~25 MB
- Channels + Guilds + Users: < 1 MB
- Attachments index: ~5 000 entries × ~200 B = ~1 MB
- **Celkem DB:** ~30 MB. Disk attachment cache separátně (500 MB default cap).

Limit/eviction zpráv: zatím **žádný** v DB. Pokud DB přeroste 500 MB, přidat retention policy (`DELETE FROM message WHERE timestamp < ?` per kanál). Řeší ADR později.
