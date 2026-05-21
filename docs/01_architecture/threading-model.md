# Threading model

Detail of ADR-0004. Concrete scopes, dispatchers, lifecycle.

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

**Important:** `ViewModelScope` is **not** a child of `ApplicationScope`. Reason — a ViewModel may outlive a re-login (e.g. a Settings screen open before logout remains open and should emit an error). Repository injection into ViewModels uses `WeakReference` or Flow with `onCompletion` cleanup.

## Dispatcher cheatsheet

| Operation | Dispatcher | Reason |
|---|---|---|
| Compose state read/write | `Dispatchers.Main.immediate` | Compose runtime constraint |
| Final `StateFlow.emit` in ViewModel | `Dispatchers.Main.immediate` | Smooth UI updates |
| SQLDelight read/write | `Dispatchers.IO` | Blocking JDBC SQLite driver |
| Ktor REST / WebSocket | `Dispatchers.IO` | Ktor internal pool, no manual switch needed |
| JSON deserialize | `Dispatchers.Default` | CPU bound |
| RichText parse | `Dispatchers.Default` | CPU bound |
| File IO (disk cache) | `Dispatchers.IO` | Blocking |
| Crypto (Opus encode, Phase 3) | dedicated `newSingleThreadContext` | Avoid pool starvation |

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

When any of these jobs fails → cancel GatewayScope → the reconnect orchestrator (in SessionScope) detects it and spawns a new GatewayScope.

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
        socket.close()                // guaranteed cleanup
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

- ❌ `GlobalScope.launch` — no owner, leak guaranteed
- ❌ `runBlocking` outside `main()` and tests
- ❌ `Thread.sleep` in a coroutine
- ❌ `Channel.receive()` on the UI thread without timeout/select
- ❌ `MutableStateFlow` exposed publicly — always `asStateFlow()`
- ❌ Shared scope for unrelated features ("MainScope" everywhere)
- ❌ `Flow.collect { ... }` without explicit scope ownership
- ❌ Creating a coroutine inside a `@Composable` outside `LaunchedEffect`/`rememberCoroutineScope`
- ❌ Recursive `Flow` chains with unclear ownership

## Debug

In dev builds, enable the coroutine debugger:

```kotlin
// JVM start args:
-Dkotlinx.coroutines.debug
-Dkotlinx.coroutines.stacktrace.recovery=true
```

Exposes coroutine names in stack traces:

```kotlin
scope.launch(CoroutineName("MessageRepository.observe(channelId=$cid)")) { ... }
```

## Test strategy

- `kotlinx-coroutines-test` `runTest` for deterministic dispatching
- `TestScope` with virtual clock for debounce/delay tests
- Custom `TestDispatcher` injection into Repository constructors
- No `Thread.sleep` in tests — always `advanceUntilIdle()` / `advanceTimeBy()`
