# Persistence schema (SQLDelight + SQLite)

Table definitions for the local cache. Implementation in `:shared:persistence-api` (interfaces) + `:desktop:persistence-sqldelight` (per-platform actual). The same schema applies on all platforms (Desktop/Android/iOS) — SQLDelight generates platform-specific drivers.

## Database location

- Linux: `$XDG_DATA_HOME/puklic/db/puklic.db` (default `~/.local/share/puklic/db/puklic.db`)
- macOS: `~/Library/Application Support/Puklic/db/puklic.db`
- Windows: `%APPDATA%/Puklic/db/puklic.db`
- Android: standard `context.getDatabasePath`
- iOS: Application Support directory

Path is provided by `PlatformPaths.databaseFile(): Path`, see [platform-abstractions.md](platform-abstractions.md).

## Pragmas

```sql
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA temp_store = MEMORY;
PRAGMA mmap_size = 268435456;     -- 256 MB
PRAGMA cache_size = -8000;        -- 8 MB
```

WAL for concurrent reads during writes. Negative `cache_size` = KB, not pages.

## Tables

### `account`

Per-token entry. The client can hold a history of several accounts (re-login).

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

The token is **not in the DB** — it is held in platform secure storage, keyed by `user_id`.

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
    guild_id INTEGER,               -- NULL for DM
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

User cache for author lookup and mention resolution.

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
    -- Structured sub-objects as JSON for simplicity:
    -- (alternatively separate tables when query access is needed)
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

**Note on JSON columns:**
- JSON serialization of sub-objects is sufficient for Phase 1
- If query access is needed ("all messages with reaction emoji X") → migrate to normalized tables `message_reaction`, `message_attachment`, etc. (resolved by ADR later)

### `attachment_cache_index`

Metadata for the disk attachment cache. Files on disk, metadata in the DB for LRU eviction.

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

An unsent message being composed per channel.

```sql
CREATE TABLE local_draft (
    channel_id INTEGER PRIMARY KEY,
    content TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (channel_id) REFERENCES channel(id) ON DELETE CASCADE
);
```

### `outbound_message`

Queue for sending (offline / retry).

```sql
CREATE TABLE outbound_message (
    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
    channel_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    attachments_json TEXT NOT NULL DEFAULT '[]',
    reference_message_id INTEGER,
    nonce TEXT NOT NULL,            -- for server idempotence
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

SQLDelight has its own migration mechanism (`Migration.kt`); `schema_version` is used only for debug / sanity checks.

## Migrations

SQLDelight migrations = a sequence of `.sqm` files (`migrations/1.sqm`, `2.sqm`, ...).

**Rules:**
- Never edit an already-shipped migration
- Every change = a new `.sqm` with `ALTER TABLE` / `CREATE INDEX` / data migration
- For a major schema change where migration is not possible, **WIPE DB** is acceptable (data is cache; the authoritative source is Discord) — but it **must be** an opt-in dialog in the UI, not a silent deletion
- Cache wipe is a separate function in Settings → "Reset local cache"

**Applied migrations:**

| File | From → To | Change |
|---|---|---|
| `migrations/1.sqm` | v1 → v2 | Add nullable `channel.bitrate` + `channel.user_limit` columns (voice channels, commit 1d550c1) |

## Performance hints

- `INSERT OR REPLACE` for upsert of events (gateway `MESSAGE_UPDATE` as full message replacement)
- Bulk insert (READY initial guild/channel state) in a single transaction
- `SELECT` for messages in UI: always `LIMIT 200` + `ORDER BY timestamp DESC`
- Pagination via `WHERE timestamp < ?` (not OFFSET)

## Database size

Estimate for a typical account (50 guilds, 500 channels, 6 months of history):
- Messages: ~50 000 messages × ~500 B = ~25 MB
- Channels + Guilds + Users: < 1 MB
- Attachments index: ~5 000 entries × ~200 B = ~1 MB
- **Total DB:** ~30 MB. Disk attachment cache separate (500 MB default cap).

Message limit/eviction: currently **none** in the DB. If the DB exceeds 500 MB, add a retention policy (`DELETE FROM message WHERE timestamp < ?` per channel). Resolved by ADR later.
