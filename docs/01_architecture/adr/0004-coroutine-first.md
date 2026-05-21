# ADR-0004: Coroutine-first architecture

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic is an event-stream application — gateway events, message updates, presence, typing, voice state, uploads, notifications. We need a unified asynchronous model across all layers (Discord protocol → repository → ViewModel → Compose).

## Decision

**Kotlin Coroutines + Flow as the sole asynchronous model.** No Reactor, RxJava, or callback-based APIs beyond platform boundaries.

### Stream types

| Type | Usage | Owner |
|---|---|---|
| `StateFlow<T>` | current state with a default value (current user, channel selection, settings) | ViewModel / Repository |
| `SharedFlow<T>` | events without retention (toast notifications, gateway dispatched events toward UI) | Session / Notification service |
| `Channel<T>` | unicast queue (gateway outbound messages, upload queue) | Session / Worker |
| `Flow<T>` (cold) | per-query streams (message list for a channel) | Repository |

### Scope hierarchy

```
ApplicationScope (SupervisorJob + Dispatchers.Default)
  ├─ SessionScope (per Discord account)
  │   ├─ GatewayScope (websocket lifecycle)
  │   └─ RepositoryScope (DB + cache)
  └─ UiScope (per Composable owner, navigation)
      └─ ViewModelScope (per screen)
```

**Rules:**
- Every scope has its own `Job` parent (SupervisorJob)
- `ViewModelScope` is disposed when navigating away from the screen
- `SessionScope` is disposed on logout
- **NEVER** `GlobalScope.launch`
- **NEVER** a shared scope for unrelated features

### Dispatcher policy

| Work | Dispatcher |
|---|---|
| UI render, Compose state updates | `Dispatchers.Main` (Compose default) |
| DB IO (SQLDelight) | `Dispatchers.IO` |
| Network IO (Ktor) | `Dispatchers.IO` (Ktor internal pool) |
| Parsing (RichText AST, JSON deserialization) | `Dispatchers.Default` |
| Heavy crypto (Opus encode, DAVE) | dedicated thread pool (later) |

### Forbidden patterns

- ❌ `runBlocking` outside `main()` and unit tests
- ❌ `GlobalScope`
- ❌ Singleton event bus (`EventBus.post(...)`)
- ❌ Callback registration without an explicit lifecycle deregistration
- ❌ `Flow.collect` without scope ownership
- ❌ Blocking calls inside a `@Composable` (no `Thread.sleep`, no sync IO)

### Cancellation

- Cancellation is first-class — all IO calls must be cancellation-cooperative
- `ensureActive()` in long computation loops
- Gateway disconnect = cancel `GatewayScope` → automatic cleanup of heartbeat / reader / writer coroutines

## Consequences

- ✅ Backpressure handled by Flow operators (`conflate`, `buffer`, `debounce`)
- ✅ Memory leaks are harder — scope is disposed with its owner
- ✅ Testability — `TestScope`, `runTest`, deterministic dispatchers
- ⚠️ Discord gateway WebSocket = manual heartbeat coroutine (Ktor WebSocket internal pingPeriod is not compatible with Discord's ack protocol)
- ⚠️ Developers must know the coroutine debugger (`-Dkotlinx.coroutines.debug`) — documented in `docs/06_ops/build.md`

## Related

- ADR-0003: Cache & RAM strategy (Flow over a bounded window)
- `docs/01_architecture/threading-model.md` (TBD) — detailed scope diagram
- `docs/01_architecture/data-flow.md` (TBD)
