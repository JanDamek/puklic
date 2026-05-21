# ADR-0004: Coroutine-first architektura

- **Status:** accepted
- **Date:** 2026-05-21
- **Deciders:** Jan Damek

## Context

Puklic je event-stream aplikace — gateway events, message updates, presence, typing, voice state, uploads, notifikace. Potřebujeme jednotný asynchronní model přes všechny vrstvy (Discord protokol → repository → ViewModel → Compose).

## Decision

**Kotlin Coroutines + Flow jako jediný asynchronní model.** Žádný Reactor, RxJava, callback-based API mimo platformní hranice.

### Stream typologie

| Typ | Použití | Vlastník |
|---|---|---|
| `StateFlow<T>` | aktuální stav s default hodnotou (current user, channel selection, settings) | ViewModel / Repository |
| `SharedFlow<T>` | events bez retention (toast notifikace, gateway dispatched events směrem do UI) | Session / Notification service |
| `Channel<T>` | unicast queue (gateway outbound messages, upload queue) | Session / Worker |
| `Flow<T>` (cold) | per-query streams (message list pro channel) | Repository |

### Scope hierarchie

```
ApplicationScope (SupervisorJob + Dispatchers.Default)
  ├─ SessionScope (per Discord account)
  │   ├─ GatewayScope (websocket lifecycle)
  │   └─ RepositoryScope (DB + cache)
  └─ UiScope (per Composable owner, navigation)
      └─ ViewModelScope (per screen)
```

**Pravidla:**
- Každá scope má vlastní `Job` parent (SupervisorJob)
- `ViewModelScope` zaniká s navigací pryč z obrazovky
- `SessionScope` zaniká s logoutem
- **NIKDY** `GlobalScope.launch`
- **NIKDY** sdílený scope pro nesouvisející featury

### Dispatcher policy

| Práce | Dispatcher |
|---|---|
| UI render, Compose state updates | `Dispatchers.Main` (Compose default) |
| DB IO (SQLDelight) | `Dispatchers.IO` |
| Network IO (Ktor) | `Dispatchers.IO` (Ktor interní pool) |
| Parsing (RichText AST, JSON deserialization) | `Dispatchers.Default` |
| Heavy crypto (Opus encode, DAVE) | dedicated thread pool (později) |

### Zakázané vzory

- ❌ `runBlocking` mimo `main()` a unit testy
- ❌ `GlobalScope`
- ❌ Singleton event bus (`EventBus.post(...)`)
- ❌ Callback registrace bez explicitní deregistrace lifecycle
- ❌ `Flow.collect` bez scope ownership
- ❌ Blocking call uvnitř `Composable` (žádný `Thread.sleep`, sync IO)

### Cancellation

- Cancellation je first-class — všechny IO calls musí být cancellation-cooperative
- `ensureActive()` v dlouhých computation loops
- Gateway disconnect = cancel `GatewayScope` → automatický cleanup heartbeat / reader / writer coroutin

## Consequences

- ✅ Backpressure handled by Flow operators (`conflate`, `buffer`, `debounce`)
- ✅ Memory leaks těžší — scope zaniká s ownerem
- ✅ Testovatelnost — `TestScope`, `runTest`, deterministic dispatchers
- ⚠️ Discord gateway websocket = manual heartbeat coroutine (Ktor WebSocket interní pingPeriod není kompatibilní s Discord ack protokolem)
- ⚠️ Vývojář musí znát coroutine debugger (`-Dkotlinx.coroutines.debug`) — bude v `docs/06_ops/build.md`

## Related

- ADR-0003: Cache & RAM strategie (Flow nad bounded oknem)
- `docs/01_architecture/threading-model.md` (TBD) — detailní scope diagram
- `docs/01_architecture/data-flow.md` (TBD)
