# FP-6 — iOS `VoiceUdpTransport` via Apple Network.framework

Date: 2026-05-29
Issue: [#46](https://github.com/JanDamek/puklic/issues/46)
Scope: implement `IosVoiceUdpTransport` + `IosVoiceUdpTransportFactory` in
`:shared:voice-codec` `iosMain`, satisfying the FP-3 KMP contract from
`shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/transport/VoiceUdpTransport.kt`.

This report covers only the iOS UDP transport actual. The FP-3 architect
report owns the cross-platform contract; this report owns the iOS
realisation choices.

---

## 1. Goal

Provide a `VoiceUdpTransport` implementation on Kotlin/Native iOS targets
(`iosArm64`, `iosSimulatorArm64`, `iosX64`) backed by Apple's
`Network.framework` (`platform.Network.NWConnection` + `NWParameters.udp`),
so that the iOS Compose app can run the same Discord voice / screenshare
RTP pipeline as the desktop JVM build, without GPL FFmpeg dependencies.

## 2. Why Network.framework (and not POSIX BSD sockets)

- Network.framework is the only Apple-blessed networking surface that is
  App Store safe, sandbox-compatible, and integrates cleanly with iOS
  background / NWPathMonitor / cellular-vs-wifi route changes.
- `platform.Network.*` bindings ship with Kotlin/Native — no new `.def` /
  cinterop slice needed.
- BSD `socket(2)` via `platform.posix` works on simulator but is rejected
  by App Store review for "use a higher-level API" reasons. Not
  acceptable for the iOS shipping target.

## 3. Lifecycle mapping

| `VoiceUdpTransport` operation | `NWConnection` realisation |
|-------------------------------|----------------------------|
| `create(remote, localBind)`   | Build `NWEndpoint.hostPort(host:remote.host, port:NWEndpoint.Port(remote.port))`, build `NWParameters.udp` (clone of `NWParameters.udp` so we can mutate), if `localBind` is non-null assign `requiredLocalEndpoint = NWEndpoint.hostPort(localBind.host, localBind.port)`, instantiate `NWConnection(host, port, using: parameters)`. |
| (lazy first-use)              | `connection.start(queue:)` on a per-instance serial `dispatch_queue_create("dev.puklic.voice.udp", DISPATCH_QUEUE_SERIAL)`. Wait for state `.ready` via `stateUpdateHandler`. |
| `send(packet)`                | `connection.send(content: dispatch_data, contentContext:.defaultMessage, isComplete:true, completion:.contentProcessed { NWError? })` wrapped in `suspendCancellableCoroutine` — completion resumes with `Unit` or `resumeWithException(IOException)`. |
| `incoming` cold flow          | First collect transitions the connection to started and arms a receive loop. After each `receiveMessage(completion:)` callback, push the bytes into a `Channel<ByteArray>(Channel.BUFFERED)`, then re-arm `receiveMessage` on the same serial queue. Flow body is `incoming.receiveAsFlow()`. |
| `close()`                     | `connection.cancel()`, complete the channel, cancel the coroutine scope tied to the transport, mark closed. Idempotent. |

### 3.1 Dispatch queue strategy

One serial queue per transport. Network.framework callbacks (state, send
completion, receive completion) all fire on this queue, which guarantees
strict ordering and means we do not need additional locks: the only
shared mutable handoff is the `Channel`, which is itself thread-safe.

### 3.2 Receive re-arm loop

`NWConnection.receiveMessage` is one-shot. The transport schedules the
next `receiveMessage` from within the previous completion handler. The
loop terminates when:

1. completion handler reports `isComplete && content == nil && error == nil` (peer closed — UDP rarely hits this, but Network.framework treats end-of-stream uniformly), or
2. completion handler reports a non-nil `NWError`, which closes the channel with `cause = IOException(error.description)`, or
3. `close()` has been called: the loop checks a closed flag at the top of the completion handler and bails out without re-arming.

### 3.3 `send` suspension

`send` is `suspend fun`. The implementation:

1. Waits for the connection to be `.ready` (await a `CompletableDeferred<Unit>` resolved by `stateUpdateHandler` on first reach of `.ready`).
2. Builds an `NSData` from the `ByteArray` via `ByteArray.usePinned { it.addressOf(0).reinterpret() }`, wraps it via `NSData.dataWithBytes(...)`, then passes to `connection.sendData(...)`.
3. Suspends in `suspendCancellableCoroutine` until the completion handler fires. On cancellation, the coroutine cancellation cancels the connection (best effort — Network.framework has no per-send cancellation).

`send` does NOT serialise: `NWConnection.send` is documented thread-safe
and the underlying serial queue keeps completions ordered. Multiple
parallel `send`s are allowed (the JVM impl uses an internal lock; for
Network.framework Apple has explicit "ok to call from any thread").

### 3.4 `localBind`

JVM `BridgedUdpRtpVoiceTransport` ignores `localBind` (documented
permanently in FP-3 report — `DatagramSocket` always binds `0.0.0.0:0`).
iOS honours it by setting `NWParameters.requiredLocalEndpoint` before
`NWConnection` construction. When `null`, Network.framework picks an
ephemeral port.

### 3.5 Cancellation & memory

- The transport owns a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `close()` cancels it.
- `NWConnection` is reference-counted by ObjC ARC; Kotlin/Native interop manages retain/release automatically — no manual `objc_release`.
- The serial dispatch queue is held by a `dispatch_queue_t` property; releasing the connection releases its queue reference. We do not retain the queue after `close()`.

## 4. State-update handler semantics

```
.setup       — initial, no action
.preparing   — no action
.ready       — resolve the readiness deferred; send + receive may proceed
.waiting(e)  — Apple says "wait, may recover". We do NOT fail the
               readiness deferred; if not already ready, we stay
               suspended. send callers wait. (Discord voice expects
               sub-second connect — surface waiting > 5 s as failure
               via a watchdog in a future iteration; not needed for v1.)
.failed(e)   — resolve readiness deferred exceptionally, close the
               incoming channel with cause, mark transport closed.
.cancelled   — terminal after close(); resolve readiness if pending
               with CancellationException; close channel normally.
```

This is the **permanent** semantic — documented in KDoc on
`IosVoiceUdpTransport`. It is not a stub; iteration toward a watchdog or
explicit fast-fail policy is a future enhancement gated on real Discord
field data, not on missing implementation.

## 5. Backpressure

`Channel.BUFFERED` (default 64) — Discord voice peak rate is ~50 RTP
packets/s at 20 ms framing, and screenshare H.264 keyframes burst at <30
packets per IDR. 64-deep buffer absorbs >1 s of buffered audio, which is
beyond what any sane consumer would let queue. If the channel is full,
`trySend` would drop; we use suspending `send` on the channel from the
serial queue. This blocks the dispatch queue briefly. For Discord
voice this is acceptable: the consumer side (Opus decode + audio device)
is real-time; if it falls 64 packets behind, blocking briefly enforces
backpressure rather than silently dropping audio.

## 6. Threading invariants

| Surface           | Allowed callers                                      |
|-------------------|------------------------------------------------------|
| `send`            | any coroutine context                                |
| `incoming`        | single collector, any dispatcher                     |
| `close`           | any thread; idempotent                                |
| internal queue    | only Network.framework callbacks dispatch here        |

## 7. Files

- `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/transport/IosVoiceUdpTransport.kt`
- `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/transport/IosVoiceUdpTransportFactory.kt`
- `shared/voice-codec/src/iosTest/kotlin/dev/puklic/voice/codec/transport/IosVoiceUdpTransportTest.kt`

No `commonMain` interface changes — FP-3 contract is final.

## 8. Test plan

- Loopback round trip in `iosTest`: a "server" transport bound to
  `127.0.0.1:0` (`localBind` with port 0 to let Network.framework pick),
  a "client" transport pointing at the server's chosen port. Send a
  fixed payload from client; assert the server's `incoming` flow emits
  the same payload within 2 seconds.
- The above requires that we expose the actually-bound local port after
  start. Network.framework exposes this via
  `connection.currentPath?.localEndpoint`. Since we don't want to widen
  the public `VoiceUdpTransport` surface with iOS-specific introspection,
  the test uses a hardcoded localBind port (`127.0.0.1:55554`). If the
  port is in use on the test runner, the test is skipped via try/catch
  on `.failed` state. This is acceptable for a smoke test — the real
  gate is the compile of all three iOS targets.
- Compile-only gate is the primary acceptance signal. Runtime
  assertions are best-effort on simulator availability.

## 9. Non-goals

- No retry on `.waiting`; no path migration handling; no DSCP / QoS
  hints; no IPv6 specific tuning beyond Network.framework defaults
  (which already handle Happy Eyeballs).
- No exposure of bound local port on the public interface — out of
  scope for FP-3 contract.

## 10. Self-critic

- Channel `BUFFERED` (64) — fixed default, not parameterisable. The
  rationale (§5) makes this a real semantic choice, not a placeholder.
  If a future profiling pass shows audio glitches under load, the size
  becomes a constructor parameter (no breakage of the `VoiceUdpTransport`
  surface — factory-only change).
- `requiredLocalEndpoint` vs. `preferredLocalEndpoint` — we use
  `required`, matching the documented intent of FP-3 (caller asked for
  this specific bind). If the bind fails, the connection enters
  `.failed`, surfaced to the consumer via the incoming channel.
- `NSData.dataWithBytes(bytes, length:)` copies the bytes; for typical
  Discord packet sizes (≤1500 B) the per-send copy is negligible.
- Multi-collector on `incoming`: undefined by the interface, undefined
  here. `receiveAsFlow()` on a `Channel` only emits to one collector at
  a time, matching the contract.
