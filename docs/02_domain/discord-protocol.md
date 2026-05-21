# Discord protokol — gateway + REST

Reference dokument pro `:shared:protocol-discord`. Cíl: shrnout to, co Puklic používá z Discord API v10, **bez** copy-paste oficiální dokumentace. Detaily endpoints → [discord.com/developers/docs](https://discord.com/developers/docs).

## REST API v10

- Base URL: `https://discord.com/api/v10`
- Auth header: `Authorization: <token>` (user token, **bez** prefixu `Bot `)
- User-Agent: `Puklic/<version> (Linux; Wayland)` — vlastní UA, **ne** simulace browseru

### Klíčové endpointy fáze 1

| Endpoint | Účel |
|---|---|
| `GET /users/@me` | Validace tokenu, fetch self-user info |
| `GET /users/@me/guilds` | Seznam guildů |
| `GET /guilds/{id}/channels` | Kanály v guildu |
| `GET /users/@me/channels` | DM kanály |
| `GET /channels/{id}/messages` | Historie zpráv (před `before=`, `after=`, `limit=` max 100) |
| `POST /channels/{id}/messages` | Odeslání zprávy |
| `PATCH /channels/{id}/messages/{mid}` | Edit |
| `DELETE /channels/{id}/messages/{mid}` | Smazat |

### Klíčové endpointy fáze 2

| Endpoint | Účel |
|---|---|
| `PUT /channels/{id}/messages/{mid}/reactions/{emoji}/@me` | Přidat reakci |
| `DELETE /channels/{id}/messages/{mid}/reactions/{emoji}/@me` | Odebrat reakci |
| `POST /channels/{id}/messages` s `multipart/form-data` | Upload attachmentu |
| `GET /channels/{id}/messages/{mid}` | Single message fetch (refresh) |

### Rate limiting

Discord vrací response headers:
- `X-RateLimit-Bucket`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset-After`
- `429 Too Many Requests` s `Retry-After`

Implementace v `:shared:protocol-discord`:
- Bucket-aware rate limiter (per route + bucket)
- Globální 429 = stop wszystkich requests + sleep
- Cancellation-cooperative — exponential backoff přes coroutine `delay`

### Error handling

- `401 Unauthorized` → token expired/invalid → emit `SessionEvent.TokenInvalid` → UI vyhodí na login
- `403 Forbidden` → user nemá permission → propagovat error do volajícího (UI zobrazí toast)
- `404 Not Found` → resource neexistuje → repository invaliduje cache
- `5xx` → retry s backoffem (3 pokusy, exponential 1s/2s/4s, pak fail)

## Gateway (WebSocket)

- URL: `wss://gateway.discord.gg/?v=10&encoding=json`
- Alternativně `?encoding=etf` (binary Erlang term format) — **pro fáze 1 zůstaneme u JSON**, ETF přidá komplexitu a šetří jen ~30 % bandwidth
- Compression: `&compress=zlib-stream` (zlib continuous stream) — **použijeme** od fáze 1, šetří 70–80 % traffic

### Lifecycle

```
1. HTTP GET /gateway → wss URL (cache 24 h)
2. WebSocket connect
3. Receive HELLO (op 10) → heartbeat_interval
4. Send IDENTIFY (op 2) s tokenem + intents + properties
5. Receive READY (op 0, event READY) → session_id, resume_gateway_url, initial state
6. Loop:
   - Send HEARTBEAT (op 1) každých heartbeat_interval ms
   - Receive HEARTBEAT_ACK (op 11) — musí dorazit před dalším heartbeat
   - Receive DISPATCH (op 0) events — MESSAGE_CREATE, TYPING_START, ...
7. Disconnect / error → close code rozhoduje:
   - Resumable codes → reconnect na resume_gateway_url, send RESUME
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

**Důležité:** Puklic **nesimuluje** browser fingerprint. `browser: "puklic"`, vlastní UA na REST. Discord toleruje custom klienty (Ripcord, Abaddon historicky), pokud se nesnaží vydávat za oficiálního.

User tokens vs bot tokens: user tokens **neposílají `intents`** stejně jako boti, ale `capabilities` flag, který určuje co server odešle. Detail dořeší implementace + test proti reálnému API.

### Events relevantní pro fáze 1

| Event | Význam |
|---|---|
| `READY` | Initial state: user, guilds, private_channels, session_id |
| `READY_SUPPLEMENTAL` | Doplňková data po READY (merged_members, guild_experiments) |
| `GUILD_CREATE` | Lazy guild data (po READY nebo při joinu) |
| `GUILD_UPDATE` / `GUILD_DELETE` | |
| `CHANNEL_CREATE` / `UPDATE` / `DELETE` | |
| `MESSAGE_CREATE` | Nová zpráva |
| `MESSAGE_UPDATE` | Edit (partial — jen změněné fieldy) |
| `MESSAGE_DELETE` | |
| `TYPING_START` | Někdo začal psát |
| `PRESENCE_UPDATE` | Status / activity uživatele |
| `MESSAGE_REACTION_ADD` / `REMOVE` | (fáze 2) |
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
| 4008 Rate limited | ✅ s backoffem |
| 4009 Session timeout | ✅ resume |
| 4010 Invalid shard | ❌ |
| 4011 Sharding required | N/A pro user |
| 4012 Invalid API version | ❌ |
| 4013 Invalid intents | ❌ |
| 4014 Disallowed intents | ❌ |

Implementace v `:shared:protocol-discord/Gateway.kt`.

### Heartbeat

- Server pošle HELLO s `heartbeat_interval` (typicky 41 250 ms)
- Klient pošle HEARTBEAT (op 1) s aktuální `seq`
- Server odpoví HEARTBEAT_ACK (op 11)
- **Pokud klient neobdrží ACK před dalším heartbeat** → assume zombie connection → close + reconnect
- První heartbeat zpozdit o `heartbeat_interval * Math.random()` (jitter) — zatížení Discord serverů

### Zlib-stream decompression

Gateway s `compress=zlib-stream` posílá continuous zlib stream:
- Single `Inflater` instance per connection
- Frame boundary = `\x00\x00\xff\xff` (Z_SYNC_FLUSH)
- Implementace: buffer příchozích bytes, hledat boundary, decompress chunk, parse JSON

Knihovny: `java.util.zip.Inflater` (JVM/Android), `zlib` (iOS přes Kotlin/Native interop).

## Capabilities & intents (user accounts)

User accounts neoznamují intents tak jako boti — místo toho `capabilities` integer flag:
- Discord vyvíjí toto interně, hodnoty se mění (typicky `16381` ke květnu 2026)
- Doporučení: použít stejnou hodnotu jako oficiální klient v dané době, sledovat v `:shared:protocol-discord/Capabilities.kt`
- Vliv: ovlivňuje co server pošle v `READY_SUPPLEMENTAL`, jak posílá guild members, atd.

## Tokeny

- User token = single string, JWT-like, ale ne validní JWT
- Začíná `M`, `N`, nebo `O` (epoch encoded)
- Token **nikdy nelogovat**, **nikdy neposílat** do crash reportu
- Storage přes `PlatformSecureStorage` (viz [platform-abstractions.md](../03_infrastructure/platform-abstractions.md))

## Snowflake parsing

```kotlin
val DISCORD_EPOCH = 1420070400000L  // 2015-01-01

fun Long.snowflakeTimestamp(): Instant =
    Instant.fromEpochMilliseconds((this shr 22) + DISCORD_EPOCH)
```

Použití: extract `created_at` pro entity, kde Discord neposílá explicitní timestamp.

## Voice & DAVE (fáze 3+)

Voice gateway = separátní WebSocket per voice channel, vlastní opcode set. Detail bude v `docs/02_domain/voice-protocol.md` (TBD při startu fáze 3).

DAVE (E2EE voice) protokol byl Discord [veřejně specifikován](https://daveprotocol.com/). Implementace dle veřejné spec, **ne** reverse-engineered z oficiálního klienta.
