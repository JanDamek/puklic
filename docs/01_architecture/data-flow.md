# Data flow

End-to-end tok dat od Discord serveru do Compose UI a zpět.

## Incoming (server → UI)

```
Discord Gateway WebSocket
        │
        │  Frame (zlib-compressed JSON)
        ▼
:shared:protocol-discord
   GatewayClient
        │  decompress → parse JSON → DispatchEvent
        ▼
   DispatchRouter            (SharedFlow<DispatchEvent>)
        │
        ├──────────────► MessageDispatcher
        │                     │  MESSAGE_CREATE / UPDATE / DELETE
        │                     ▼
        │                 :shared:repositories
        │                 MessageRepository
        │                     │  1. parse DiscordMessageDto → ChatMessage
        │                     │  2. parse content → RichTextDocument (chat-parser)
        │                     │  3. persist to SQLite
        │                     │  4. push do RAM hot/warm cache
        │                     ▼
        │                 Flow<List<ChatMessage>>  (per channel)
        │
        ├──────────────► GuildDispatcher
        │                     │  GUILD_CREATE / UPDATE / DELETE
        │                     ▼
        │                 GuildRepository → StateFlow<Map<GuildId, Guild>>
        │
        ├──────────────► ChannelDispatcher → ChannelRepository
        ├──────────────► PresenceDispatcher → PresenceRepository
        ├──────────────► TypingDispatcher → TypingRepository (ephemeral)
        └──────────────► UserDispatcher → UserRepository

Repositories ────────► ViewModels
        │                     │  combine, map, debounce
        │                     ▼
        │              StateFlow<ScreenState>
        │                     │
        ▼                     ▼
   SQLite (persist)    Compose UI (collectAsState)
                              │
                              ▼
                         RichTextView
                          (resolvers → Repository lookups)
```

### Klíčová pravidla

1. **Gateway → Repository:** Jednosměrný tok. Repository je jediný consumer dispatched events pro daný typ.
2. **Repository → ViewModel:** Hot/cold Flow. ViewModel mapuje na UI state, debounce/conflate dle potřeby.
3. **ViewModel → UI:** `StateFlow<ScreenState>` collected jako `state.collectAsState()`. Žádný `LiveData`, žádný `Observable`.
4. **Persistence:** Repository je vlastník DB writes. Žádný „direct DB access" z ViewModelu nebo UI.
5. **Parsing:** Rich text se parsuje při příchodu zprávy z gateway (před uložením do RAM cache). Nikdy v UI.

## Outgoing (UI → server)

### Send message flow

```
User types in MessageComposer
        │
        ▼
   ComposerViewModel
        │  draft persistence (debounce 500 ms → local_draft table)
        │
        │  on submit:
        ▼
   MessageRepository.send(channelId, content, attachments)
        │  1. INSERT do outbound_message s state=pending, generate nonce
        │  2. Optimistic insert do message hot cache (state=Sending)
        │  3. UI okamžitě vidí svou zprávu (s loading indikátorem)
        ▼
   OutboundMessageWorker (coroutine v SessionScope)
        │  pickup pending → REST POST /channels/{id}/messages
        │
        ├─ Success ──► server vrátí ChatMessage → REPLACE optimistic v RAM + DB
        │              DELETE z outbound_message
        │
        └─ Failure ──► state=failed, last_error=..., retry s backoffem
                       UI vidí ⚠ ikonu na zprávě
```

### Edit / delete

- Edit: `MessageRepository.edit(id, newContent)` → PATCH + optimistic update v RAM.
- Delete: `MessageRepository.delete(id)` → DELETE + optimistic remove z RAM. Server pak pošle MESSAGE_DELETE event, který je no-op (už jsme smazali).

### Typing indicator

```
User types → ComposerViewModel.onTyping()
        │  throttle: pošli max 1× per 5 s per channel
        ▼
   DiscordRestClient.startTyping(channelId)
        │  POST /channels/{id}/typing
        ▼
   (no response body, fire-and-forget)
```

### Reactions, uploads, atd.

Analogický pattern: ViewModel → Repository → REST client → optimistic update → server confirm.

## Session lifecycle

```
App start
   │
   ▼
SessionManager (singleton v :shared:session)
   │  check SecureStorage.get("token")
   │
   ├─ no token ──► UI shows LoginScreen
   │                LoginViewModel.submitToken() ─► SessionManager.startSession(token)
   │
   └─ token exists ──► SessionManager.startSession(token)
        │
        ▼
   DiscordSession (per token)
        │  1. REST GET /users/@me — validate
        │  2. Hydrate UI from SQLite (immediate, offline-first)
        │  3. Connect gateway, IDENTIFY
        │  4. Receive READY → reconcile state (server is authoritative)
        │  5. SharedFlow<SessionState> emits Ready
        │
        ▼
   UI navigates to MainScreen
   Repositories now have live data

Logout
   │
   ▼
SessionManager.endSession()
   │  1. Cancel SessionScope (kaskádově gateway, repositories, workers)
   │  2. Smaž token z SecureStorage
   │  3. (opt) Wipe DB
   │  4. UI naviguje na LoginScreen
```

## Offline behavior

- App start bez sítě:
  1. SecureStorage načte token
  2. SQLite hydratuje UI (guildy, channels, poslední zprávy)
  3. Gateway connect failuje → SessionState.Connecting → Retry s backoffem
  4. UI zobrazí offline banner, vše read-only
- Send message offline:
  1. Insert do `outbound_message` s state=pending
  2. UI ukáže zprávu s ⏳ indikátorem
  3. Při návratu connection: worker pickup → resend
- Po reconnectu: gateway pošle RESUME → server zaplní missed events → repositories reconciliují

## Reconciliation

Při RESUME/READY se SQLite cache může lišit od serveru (msg deleted while offline, channel removed). Repository:
- Pro guildy/channels/users: replace SQLite mirror z READY (server je truth)
- Pro messages: nezahazuj SQLite. Gateway pošle MESSAGE_DELETE pro chybějící, MESSAGE_CREATE pro nové od `seq`.
- Detect missed messages: porovnej last_message_id z READY guild s SQLite — pokud rozdíl, lazy-fetch on scroll.

## Backpressure

- Gateway → repositories: `SharedFlow<DispatchEvent>` s `extraBufferCapacity = 64`, `onBufferOverflow = SUSPEND` (drop nelze — ztratili bychom event)
- Repository → UI: ViewModel `conflate()` u toků, které jsou observační (presence updates). Pro chat messages `Flow<List<ChatMessage>>` nepoužívá conflate (každý update relevant).
- Outbound: `Channel<OutboundTask>` s `BUFFERED` capacity, worker konzumuje sekvenčně.

## Threading

| Práce | Scope | Dispatcher |
|---|---|---|
| Gateway WebSocket IO | GatewayScope | IO |
| Heartbeat coroutine | GatewayScope | Default |
| JSON parse / RichText parse | (callsite) | Default |
| SQLite read/write | RepositoryScope | IO |
| REST calls | RepositoryScope / SessionScope | IO (Ktor interní) |
| ViewModel logic | ViewModelScope | Default → Main switch only for final state emit |
| Compose render | (Compose runtime) | Main |

Detail viz [threading-model.md](threading-model.md).
