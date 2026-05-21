# Threading model

Detailizace ADR-0004. Konkrétní scopes, dispatchers, lifecycle.

## Scope hierarchy

```
ApplicationScope
  Job:        SupervisorJob
  Dispatcher: Dispatchers.Default
  Lifetime:   process lifetime
  Owner:      Application bootstrap (main() / Application.onCreate / iOS app delegate)
  Purpose:    Long-running app services (NotificationService listener, system idle watcher)

    │
    └─ SessionScope                                  (one per logged-in account)
         Job:        SupervisorJob(parent=Application)
         Dispatcher: Dispatchers.Default
         Lifetime:   login → logout
         Owner:      SessionManager
         Purpose:    Repositories, OutboundMessageWorker, SessionManager state machine

              │
              └─ GatewayScope
                   Job:        Job(parent=Session)
                   Dispatcher: Dispatchers.IO
                   Lifetime:   WebSocket connect → disconnect (cancelled on session end)
                   Owner:      GatewayClient
                   Purpose:    WebSocket reader, writer, heartbeat coroutines

ViewModelScope                                       (one per ViewModel = one per screen)
  Job:        SupervisorJob(parent=null)             -- bound by ViewModel disposal, not Application
  Dispatcher: Dispatchers.Default                    -- final emit switches to Main
  Lifetime:   ViewModel created → ViewModel cleared
  Owner:      ViewModel class
  Purpose:    Per-screen async work, StateFlow collection from repositories
```

**Důležité:** `ViewModelScope` **není** dítětem `ApplicationScope`. Důvod — ViewModel může přežít re-login (např. Settings screen otevřený před logoutem zůstává a má hodit error). Repository injection do ViewModelu používá `WeakReference` nebo Flow s `onCompletion` cleanup.

## Dispatcher cheatsheet

| Operation | Dispatcher | Reason |
|---|---|---|
| Compose state read/write | `Dispatchers.Main.immediate` | Compose runtime constraint |
| Final `StateFlow.emit` v ViewModelu | `Dispatchers.Main.immediate` | Smooth UI updates |
| SQLDelight read/write | `Dispatchers.IO` | Blocking JDBC SQLite driver |
| Ktor REST / WebSocket | `Dispatchers.IO` | Ktor interní pool, manual switch ne nutný |
| JSON deserialize | `Dispatchers.Default` | CPU bound |
| RichText parse | `Dispatchers.Default` | CPU bound |
| File IO (disk cache) | `Dispatchers.IO` | Blocking |
| Crypto (Opus encode, fáze 3) | dedicated `newSingleThreadContext` | Avoid pool starvation |

## Coroutine patterns

### Repository — Flow exposed, write suspending

```kotlin
class MessageRepository(
    private val scope: CoroutineScope,           // SessionScope
    private val db: PuklicDatabase,
    private val rest: DiscordRestClient,
) {
    private val perChannelCache = mutableMapOf<ChannelId, MutableStateFlow<List<ChatMessage>>>()

    fun observe(channelId: ChannelId): Flow<List<ChatMessage>> =
        perChannelCache.getOrPut(channelId) {
            MutableStateFlow(emptyList()).also { state ->
                scope.launch(Dispatchers.IO) {
                    state.value = db.queries.messagesByChannel(channelId.value, limit = 200).executeAsList()
                }
            }
        }.asStateFlow()

    suspend fun send(channelId: ChannelId, content: String): Result<MessageId> = withContext(Dispatchers.IO) {
        // ...
    }
}
```

### ViewModel — collect & expose

```kotlin
class MessageListViewModel(
    private val channelId: ChannelId,
    private val messages: MessageRepository,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<MessageListState> = messages.observe(channelId)
        .map { msgs -> MessageListState.Loaded(msgs) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), MessageListState.Loading)

    fun onSendClicked(content: String) {
        scope.launch {
            messages.send(channelId, content)
        }
    }

    fun close() { scope.cancel() }
}
```

### Gateway WebSocket — three coroutines

```
GatewayScope
  ├─ readerJob:    while (isActive) { socket.incoming.receive() → parse → dispatch }
  ├─ writerJob:    outboundChannel.consumeEach { socket.outgoing.send(it) }
  └─ heartbeatJob: while (isActive) { delay(interval); send Heartbeat; check ack }
```

Když kterýkoli z těchto jobů zfailí → cancel GatewayScope → reconnect orchestrator (v SessionScope) detekuje a spawne nový GatewayScope.

## Cancellation patterns

### Cooperative cancellation

```kotlin
suspend fun longParse(text: String): RichTextDocument {
    val tokens = mutableListOf<Token>()
    var pos = 0
    while (pos < text.length) {
        ensureActive()                // check every iteration
        // ... lex one token
    }
    return parseBlocks(tokens)
}
```

### Cleanup on cancel

```kotlin
val websocketJob = scope.launch {
    try {
        // ... read loop
    } finally {
        socket.close()                // garantovaný cleanup
    }
}
```

### Timeout

```kotlin
suspend fun fetchWithTimeout(url: String): Response = withTimeout(10_000) {
    httpClient.get(url)
}
```

## SharedFlow vs StateFlow vs Channel

| Need | Use |
|---|---|
| Current value with default, always emit on collect | `StateFlow` |
| Events (no retention), broadcast to many | `SharedFlow(replay=0)` |
| Events with replay (last N) | `SharedFlow(replay=N)` |
| 1:1 producer-consumer, backpressure-aware | `Channel` |
| Hot stream of work units | `Channel` consumed by worker coroutine |

### Examples

| Use case | Type |
|---|---|
| Current self-user | `StateFlow<User?>` |
| Selected guild/channel | `StateFlow<NavigationState>` |
| Gateway dispatched events | `SharedFlow<DispatchEvent>(replay=0, extraBufferCapacity=64)` |
| Toast notifications | `SharedFlow<Toast>(replay=0)` |
| Outbound message queue | `Channel<OutboundTask>(capacity=Channel.BUFFERED)` |
| Per-channel messages | `Flow<List<ChatMessage>>` cold, backed by SQLDelight |

## Anti-patterns (forbidden)

- ❌ `GlobalScope.launch` — žádný owner, leak guaranteed
- ❌ `runBlocking` mimo `main()` a testy
- ❌ `Thread.sleep` v coroutine
- ❌ `Channel.receive()` ve UI threadu bez timeout/select
- ❌ `MutableStateFlow` exposovaný public — vždy `asStateFlow()`
- ❌ Sdílený scope pro nesouvisející featury („MainScope" everywhere)
- ❌ `Flow.collect { ... }` bez explicit scope ownership
- ❌ Vytvoření coroutine v `@Composable` mimo `LaunchedEffect`/`rememberCoroutineScope`
- ❌ Recursive `Flow` chains s nejasným ownership

## Debug

V dev buildech enable coroutine debugger:

```kotlin
// JVM start args:
-Dkotlinx.coroutines.debug
-Dkotlinx.coroutines.stacktrace.recovery=true
```

Vystaví coroutine name v stack traces:

```kotlin
scope.launch(CoroutineName("MessageRepository.observe(channelId=$cid)")) { ... }
```

## Test strategie

- `kotlinx-coroutines-test` `runTest` pro deterministic dispatching
- `TestScope` s virtual clock pro debounce/delay testy
- Custom `TestDispatcher` injection do Repository konstruktorů
- Žádný `Thread.sleep` v testech — vždy `advanceUntilIdle()` / `advanceTimeBy()`
