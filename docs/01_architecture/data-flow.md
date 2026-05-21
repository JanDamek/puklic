# Data flow

End-to-end flow of data from the Discord server into the Compose UI and back.

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
        │                     │  4. push to RAM hot/warm cache
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

### Key rules

1. **Gateway → Repository:** Unidirectional flow. The Repository is the sole consumer of dispatched events for a given type.
2. **Repository → ViewModel:** Hot/cold Flow. The ViewModel maps to UI state, debouncing/conflating as needed.
3. **ViewModel → UI:** `StateFlow<ScreenState>` collected as `state.collectAsState()`. No `LiveData`, no `Observable`.
4. **Persistence:** The Repository owns DB writes. No "direct DB access" from ViewModel or UI.
5. **Parsing:** Rich text is parsed when a message arrives from the gateway (before being stored in the RAM cache). Never in the UI.

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
        │  1. INSERT into outbound_message with state=pending, generate nonce
        │  2. Optimistic insert into message hot cache (state=Sending)
        │  3. UI immediately sees the message (with a loading indicator)
        ▼
   OutboundMessageWorker (coroutine in SessionScope)
        │  pickup pending → REST POST /channels/{id}/messages
        │
        ├─ Success ──► server returns ChatMessage → REPLACE optimistic in RAM + DB
        │              DELETE from outbound_message
        │
        └─ Failure ──► state=failed, last_error=..., retry with backoff
                       UI shows ⚠ icon on the message
```

### Edit / delete

- Edit: `MessageRepository.edit(id, newContent)` → PATCH + optimistic update in RAM.
- Delete: `MessageRepository.delete(id)` → DELETE + optimistic remove from RAM. The server then sends a MESSAGE_DELETE event, which is a no-op (already deleted locally).

### Typing indicator

```
User types → ComposerViewModel.onTyping()
        │  throttle: send at most 1× per 5 s per channel
        ▼
   DiscordRestClient.startTyping(channelId)
        │  POST /channels/{id}/typing
        ▼
   (no response body, fire-and-forget)
```

### Reactions, uploads, etc.

Same pattern: ViewModel → Repository → REST client → optimistic update → server confirm.

## Session lifecycle

```
App start
   │
   ▼
SessionManager (singleton in :shared:session)
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
   │  1. Cancel SessionScope (cascades to gateway, repositories, workers)
   │  2. Delete token from SecureStorage
   │  3. (opt) Wipe DB
   │  4. UI navigates to LoginScreen
```

## Offline behavior

- App start without network:
  1. SecureStorage loads the token
  2. SQLite hydrates the UI (guilds, channels, recent messages)
  3. Gateway connect fails → SessionState.Connecting → retry with backoff
  4. UI shows an offline banner, everything is read-only
- Send message offline:
  1. Insert into `outbound_message` with state=pending
  2. UI shows the message with a ⏳ indicator
  3. On connection return: worker picks up → resend
- After reconnect: gateway sends RESUME → server delivers missed events → repositories reconcile

## Reconciliation

On RESUME/READY the SQLite cache may differ from the server (message deleted while offline, channel removed). Repository:
- For guilds/channels/users: replace the SQLite mirror from READY (server is truth)
- For messages: do not discard SQLite. Gateway sends MESSAGE_DELETE for missing messages, MESSAGE_CREATE for new ones since `seq`.
- Detect missed messages: compare last_message_id from READY guild with SQLite — if there is a difference, lazy-fetch on scroll.

## Backpressure

- Gateway → repositories: `SharedFlow<DispatchEvent>` with `extraBufferCapacity = 64`, `onBufferOverflow = SUSPEND` (dropping is not an option — we would lose events)
- Repository → UI: ViewModel uses `conflate()` for observational streams (presence updates). For chat messages `Flow<List<ChatMessage>>` does not conflate (every update is relevant).
- Outbound: `Channel<OutboundTask>` with `BUFFERED` capacity, worker consumes sequentially.

## Threading

| Work | Scope | Dispatcher |
|---|---|---|
| Gateway WebSocket IO | GatewayScope | IO |
| Heartbeat coroutine | GatewayScope | Default |
| JSON parse / RichText parse | (callsite) | Default |
| SQLite read/write | RepositoryScope | IO |
| REST calls | RepositoryScope / SessionScope | IO (Ktor internal) |
| ViewModel logic | ViewModelScope | Default → Main switch only for final state emit |
| Compose render | (Compose runtime) | Main |

Detail see [threading-model.md](threading-model.md).
