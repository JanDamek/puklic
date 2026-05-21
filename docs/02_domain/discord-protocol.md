# Discord protocol — gateway + REST

Reference document for `:shared:protocol-discord`. Goal: summarize what Puklic uses from Discord API v10, **without** copy-pasting the official documentation. Endpoint details → [discord.com/developers/docs](https://discord.com/developers/docs).

## REST API v10

- Base URL: `https://discord.com/api/v10`
- Auth header: `Authorization: <token>` (user token, **without** the `Bot ` prefix)
- User-Agent: `Puklic/<version> (Linux; Wayland)` — custom UA, **not** simulating a browser

### Key endpoints Phase 1

| Endpoint | Purpose |
|---|---|
| `GET /users/@me` | Validate token, fetch self-user info |
| `GET /users/@me/guilds` | List of guilds |
| `GET /guilds/{id}/channels` | Channels in a guild |
| `GET /users/@me/channels` | DM channels |
| `GET /channels/{id}/messages` | Message history (`before=`, `after=`, `limit=` max 100) |
| `POST /channels/{id}/messages` | Send a message |
| `PATCH /channels/{id}/messages/{mid}` | Edit |
| `DELETE /channels/{id}/messages/{mid}` | Delete |

### Key endpoints Phase 2

| Endpoint | Purpose |
|---|---|
| `PUT /channels/{id}/messages/{mid}/reactions/{emoji}/@me` | Add reaction |
| `DELETE /channels/{id}/messages/{mid}/reactions/{emoji}/@me` | Remove reaction |
| `POST /channels/{id}/messages` with `multipart/form-data` | Upload attachment |
| `GET /channels/{id}/messages/{mid}` | Single message fetch (refresh) |

### Rate limiting

Discord returns response headers:
- `X-RateLimit-Bucket`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset-After`
- `429 Too Many Requests` with `Retry-After`

Implementation in `:shared:protocol-discord`:
- Bucket-aware rate limiter (per route + bucket)
- Global 429 = stop all requests + sleep
- Cancellation-cooperative — exponential backoff via coroutine `delay`

### Error handling

- `401 Unauthorized` → token expired/invalid → emit `SessionEvent.TokenInvalid` → UI redirects to login
- `403 Forbidden` → user lacks permission → propagate error to caller (UI shows a toast)
- `404 Not Found` → resource does not exist → repository invalidates cache
- `5xx` → retry with backoff (3 attempts, exponential 1s/2s/4s, then fail)

## Gateway (WebSocket)

- URL: `wss://gateway.discord.gg/?v=10&encoding=json`
- Alternatively `?encoding=etf` (binary Erlang term format) — **for Phase 1 we stay with JSON**, ETF adds complexity and saves only ~30 % bandwidth
- Compression: `&compress=zlib-stream` (zlib continuous stream) — **we will use this** from Phase 1, saves 70–80 % traffic

### Lifecycle

```
1. HTTP GET /gateway → wss URL (cache 24 h)
2. WebSocket connect
3. Receive HELLO (op 10) → heartbeat_interval
4. Send IDENTIFY (op 2) with token + intents + properties
5. Receive READY (op 0, event READY) → session_id, resume_gateway_url, initial state
6. Loop:
   - Send HEARTBEAT (op 1) every heartbeat_interval ms
   - Receive HEARTBEAT_ACK (op 11) — must arrive before the next heartbeat
   - Receive DISPATCH (op 0) events — MESSAGE_CREATE, TYPING_START, ...
7. Disconnect / error → close code decides:
   - Resumable codes → reconnect to resume_gateway_url, send RESUME
   - Non-resumable → fresh connect + IDENTIFY
```

### IDENTIFY payload

```json
{
  "op": 2,
  "d": {
    "token": "<user token>",
    "properties": {
      "os": "linux",
      "browser": "puklic",
      "device": "puklic"
    },
    "intents": <bitmask>,
    "compress": false,
    "large_threshold": 50,
    "capabilities": <int>
  }
}
```

**Important:** Puklic **does not simulate** a browser fingerprint. `browser: "puklic"`, custom UA on REST. Discord tolerates custom clients (Ripcord, Abaddon historically) as long as they do not try to impersonate the official client.

User tokens vs bot tokens: user tokens do not send `intents` the same way bots do; instead they send a `capabilities` flag that determines what the server sends. The details will be resolved during implementation + testing against the real API.

### Events relevant for Phase 1

| Event | Meaning |
|---|---|
| `READY` | Initial state: user, guilds, private_channels, session_id |
| `READY_SUPPLEMENTAL` | Supplementary data after READY (merged_members, guild_experiments) |
| `GUILD_CREATE` | Lazy guild data (after READY or on join) |
| `GUILD_UPDATE` / `GUILD_DELETE` | |
| `CHANNEL_CREATE` / `UPDATE` / `DELETE` | |
| `MESSAGE_CREATE` | New message |
| `MESSAGE_UPDATE` | Edit (partial — only changed fields) |
| `MESSAGE_DELETE` | |
| `TYPING_START` | Someone started typing |
| `PRESENCE_UPDATE` | User status / activity |
| `MESSAGE_REACTION_ADD` / `REMOVE` | (Phase 2) |
| `USER_UPDATE` | Self-user change |

### Resumable vs non-resumable close codes

| Code | Resumable |
|---|---|
| 4000 Unknown error | ✅ |
| 4001 Unknown opcode | ❌ |
| 4002 Decode error | ❌ |
| 4003 Not authenticated | ❌ |
| 4004 Auth failed (bad token) | ❌ → emit TokenInvalid |
| 4005 Already authenticated | ❌ |
| 4007 Invalid seq | ✅ (fresh identify) |
| 4008 Rate limited | ✅ with backoff |
| 4009 Session timeout | ✅ resume |
| 4010 Invalid shard | ❌ |
| 4011 Sharding required | N/A for user |
| 4012 Invalid API version | ❌ |
| 4013 Invalid intents | ❌ |
| 4014 Disallowed intents | ❌ |

Implementation in `:shared:protocol-discord/Gateway.kt`.

### Heartbeat

- Server sends HELLO with `heartbeat_interval` (typically 41 250 ms)
- Client sends HEARTBEAT (op 1) with the current `seq`
- Server responds with HEARTBEAT_ACK (op 11)
- **If the client does not receive ACK before the next heartbeat** → assume zombie connection → close + reconnect
- First heartbeat delayed by `heartbeat_interval * Math.random()` (jitter) — reduces load on Discord servers

### Zlib-stream decompression

Gateway with `compress=zlib-stream` sends a continuous zlib stream:
- Single `Inflater` instance per connection
- Frame boundary = `\x00\x00\xff\xff` (Z_SYNC_FLUSH)
- Implementation: buffer incoming bytes, find boundary, decompress chunk, parse JSON

Libraries: `java.util.zip.Inflater` (JVM/Android), `zlib` (iOS via Kotlin/Native interop).

## Capabilities & intents (user accounts)

User accounts do not declare intents the same way bots do — instead they send a `capabilities` integer flag:
- Discord develops this internally; values change (typically `16381` as of May 2026)
- Recommendation: use the same value as the official client at that time, tracked in `:shared:protocol-discord/Capabilities.kt`
- Impact: affects what the server sends in `READY_SUPPLEMENTAL`, how it sends guild members, etc.

## Tokens

- User token = single string, JWT-like but not a valid JWT
- Starts with `M`, `N`, or `O` (epoch encoded)
- Token **must never be logged**, **must never be sent** to a crash reporter
- Storage via `PlatformSecureStorage` (see [platform-abstractions.md](../03_infrastructure/platform-abstractions.md))

## Snowflake parsing

```kotlin
val DISCORD_EPOCH = 1420070400000L  // 2015-01-01

fun Long.snowflakeTimestamp(): Instant =
    Instant.fromEpochMilliseconds((this shr 22) + DISCORD_EPOCH)
```

Usage: extract `created_at` for entities where Discord does not send an explicit timestamp.

## Voice & DAVE (Phase 3+)

Voice gateway = separate WebSocket per voice channel, with its own opcode set. Details will be in `docs/02_domain/voice-protocol.md` (TBD at the start of Phase 3).

DAVE (E2EE voice) protocol was [publicly specified by Discord](https://daveprotocol.com/). Implementation follows the public spec, **not** reverse-engineered from the official client.
