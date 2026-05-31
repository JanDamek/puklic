# FP-14h-2b implementation plan (impl-role architect step)

Scope: execute §3 of `2026-05-29-fp14h-1-v2-voice-gateway-redesign.md` —
delete the legacy `UdpRtpTransport` interface + `expect newUdpRtpTransport()`
+ JVM actual, migrate all callers (5 production, 6 tests) to the FP-3
`VoiceUdpTransport` contract.

Issue: #66. Predecessor commit: `618c1f7` (FP-14h-2a — opt-in marker +
`UdpRtpTransport` promoted to `public @PuklicVoiceCodec` to unblock
this slice's deletion).

References:
- v2 redesign §3 (IpDiscovery split + UdpRtpTransport deletion)
- v2 redesign §9.4 (caller migration map)
- FP-3 contract: `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/transport/VoiceUdpTransport.kt`

---

## §1 File inventory — current state (verified)

### 1.1 Files to delete (2)

| Path | Lines | Reason |
|---|---|---|
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.kt` | 1-42 | Legacy `interface UdpRtpTransport` + `expect newUdpRtpTransport()` + `discoverIp` extension. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.jvm.kt` | 1-55 | JVM `actual fun newUdpRtpTransport()` + `JvmUdpRtpTransport` `DatagramSocket` impl. |

### 1.2 File to create (1)

| Path | Purpose |
|---|---|
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscoveryRunner.kt` | `discoverIp` extension fun on `VoiceUdpTransport`. |

### 1.3 Production callers to migrate (5)

| # | File | Migration |
|---|---|---|
| 1 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/DefaultVoiceClient.kt` | Replace `UdpRtpTransport?` field + `newUdpRtpTransport()` construction with `VoiceUdpTransport?` constructed via `JvmVoiceUdpTransportFactory.create(Endpoint(host, port), null)`. `t.bind()` + `t.connect(host, port)` are deleted (factory handles binding lazily on first send/incoming). `t.discoverIp(ssrc)` now resolves via the new extension on `VoiceUdpTransport`. |
| 2 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/DefaultScreenShareClient.kt` | Replace `private val udpTransport: UdpRtpTransport` parameter type with `VoiceUdpTransport`. No call-site changes (only `udp.send(packet)` is invoked which is identical). |
| 3 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | Replace `private val udp: UdpRtpTransport` parameter type with `VoiceUdpTransport`. |
| 4 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | Replace `private val udp: UdpRtpTransport` parameter type with `VoiceUdpTransport`. |
| 5 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | Replace `private val transport: UdpRtpTransport` with `VoiceUdpTransport`. Receive loop `transport.receive()` becomes `transport.incoming.collect { packet -> route(packet) }`. |
| 5b | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | Replace `private val transport: UdpRtpTransport` with `VoiceUdpTransport`. Legacy fallback `packetSource ?: { transport.receive() }` is rewritten: when `packetSource` is null, the receive loop collects `transport.incoming` directly. |

(5 + 1 = 6 production files; v2 plan lists DefaultScreenShareClient as
a caller but the dispatcher + playback share the receive-loop shape
change. PlaybackPipeline is counted alongside VoicePacketDispatcher
since both are receive-loop owners — the brief's "5 production callers"
count maps to the 5 distinct classes plus the dispatcher class. Total
of 6 distinct .kt files edited in production; this matches the spirit
of the brief.)

### 1.4 Bridge file in JvmVoiceUdpTransportFactory

The existing `BridgedUdpRtpVoiceTransport` (in
`JvmVoiceUdpTransportFactory.kt`) currently wraps the legacy
`UdpRtpTransport`. Since the legacy interface goes away, this bridge
is replaced by an **in-line** `JvmVoiceUdpTransport` that owns the
`java.net.DatagramSocket` directly — verbatim port of `JvmUdpRtpTransport`
from `UdpRtpTransport.jvm.kt`, adapted to the FP-3 contract
(`incoming: Flow<ByteArray>` via `flow { while (true) emit(receiveBlocking()) }`).
This file edit is the conceptual replacement for delete #2 above.

### 1.5 jvmTest files to migrate (6)

| # | File | Migration |
|---|---|---|
| 1 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/transport/UdpRtpTransportTest.kt` | **DELETE** — tests the legacy interface that no longer exists. Equivalent contract coverage lives in `VoiceUdpTransportContractTest` (voice-codec commonTest) plus the JVM socket-loopback exercise will be added when FP-3's JVM-actual gets a dedicated test (out of scope for FP-14h-2b per brief: "no new assertions"). Deletion is mandated by FP-14h-1-v2 §3 — the legacy contract is gone, there is nothing to test. |
| 2 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/pipeline/PlaybackPipelineTest.kt` | `FakeTransport : UdpRtpTransport` → `FakeTransport : VoiceUdpTransport`. Convert `receive()` queue to a `Channel<ByteArray>` consumed via `incoming: Flow<ByteArray>` (`channel.receiveAsFlow()`). `bind`/`connect` overrides deleted. |
| 3 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSenderTest.kt` | `CapturingTransport : UdpRtpTransport` → `CapturingTransport : VoiceUdpTransport`. Drop `bind`/`connect`/`receive` overrides; `incoming: Flow<ByteArray> = emptyFlow()` (never read by the sender under test). |
| 4 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/transport/VideoRtpSenderTest.kt` | Same as #3. |
| 5 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/transport/VideoRtpSenderVp8Test.kt` | Same as #3. |
| 6 | `shared/voice/src/jvmTest/kotlin/dev/puklic/voice/screenshare/DefaultScreenShareClientTest.kt` | Same as #3 (`CapturingTransport` constructor parameter for `newClient` retyped `VoiceUdpTransport`). |

Total: 6 test files. Edits limited to (a) renaming the interface
implemented, (b) replacing `receive` queue → `incoming` Flow, (c)
dropping `bind`/`connect` overrides. NO new assertions added.

---

## §2 `discoverIp` extension contract

New file `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscoveryRunner.kt`:

```kotlin
package dev.puklic.voice.transport

import dev.puklic.voice.codec.PuklicVoiceCodec
import dev.puklic.voice.codec.transport.VoiceUdpTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@PuklicVoiceCodec
public suspend fun VoiceUdpTransport.discoverIp(
    ssrc: Int,
    timeoutMs: Long = 5_000L,
): IpDiscovery.Result {
    send(IpDiscovery.buildRequest(ssrc))
    val response = withTimeout(timeoutMs) { incoming.first() }
    return IpDiscovery.parseResponse(response)
}
```

`incoming.first()` semantics: collects the first emission then cancels
the upstream — for the JVM bridge that simply reads one datagram from
the socket via the inner `flow { while (true) emit(...) }` block. Same
on-wire behaviour as the legacy `receive()` path.

---

## §3 Self-critic

1. **Receive-loop semantics drift** — Legacy `receive()` was pull-based
   (one packet per call). FP-3 `incoming` is a cold Flow. For the
   dispatcher and playback loop, switching to `.collect { ... }` is
   semantically equivalent (one emission per UDP packet) but Flow
   cancellation semantics differ: when the surrounding coroutine is
   cancelled, the Flow collector unwinds via CancellationException. The
   existing `try { receive() } catch (_) { return }` block becomes
   `try { incoming.collect { handle(it) } } catch (_) { /* exit */ }`.
   This is a structural rewrite, not a behavioural change.
2. **Multi-collector** — VoiceUdpTransport doc states "multi-collector
   semantics are NOT defined — single-collector". In production both
   dispatcher and playback never collect simultaneously (playback
   bypasses the transport when `packetSource` is set, which is always
   true in `DefaultVoiceClient`). The legacy fallback path
   `transport.receive()` in PlaybackPipeline (used only by tests with a
   pre-loaded FakeTransport) becomes `incoming.collect`. Single-collector
   honoured. ✅
3. **IpDiscovery `incoming.first()` cancellation** — Cancelling the
   transient collector raised by `first()` does NOT close the underlying
   socket; subsequent `send` / Flow collections still work. The JVM
   bridge's `flow { while (true) emit(delegate.receive()) }` model
   restarts cleanly per collector. ✅
4. **Test fakes for `incoming: Flow<ByteArray>`** — The PlaybackPipelineTest
   fake currently exposes a `ConcurrentLinkedQueue`. Replacing with
   `Channel<ByteArray>(UNLIMITED).receiveAsFlow()` preserves the
   "offer + poll" idiom and avoids busy-wait. ✅
5. **VoicePacketDispatcher imports `kotlinx.coroutines.flow.collect`** —
   already on classpath via `:shared:voice` commonMain.
6. **JvmVoiceUdpTransport `incoming` lazy bind** — The existing
   `BridgedUdpRtpVoiceTransport` lazily initialises via a Mutex. The
   replacement inlined-DatagramSocket implementation keeps the same
   lazy-bind pattern, just without the inner delegate layer. ✅
7. **HARD RULE #2 audit** — Zero TODO, zero deprecation shims, zero
   stub legs. The legacy interface is permanently gone; the new bridge
   class owns the socket directly. ✅

---

## §4 Verification matrix (commands)

1. `./gradlew :shared:voice:build`
2. `./gradlew :shared:voice-codec:build`
3. `./gradlew :shared:voice-codec:compileKotlinIosArm64 :shared:voice-codec:compileKotlinIosX64 :shared:voice-codec:compileKotlinIosSimulatorArm64`
4. `./gradlew :ios:app:verifyIosNoGplDeps`
5. `./gradlew :desktop:app:verifyMacAppStoreNoGplDeps`
6. `./gradlew :desktop:app:macAppStoreTest --no-configuration-cache`
7. `./gradlew :shared:voice:test :shared:voice-codec:test`

All must be GREEN before push.

---

## §5 Commit + close

```
refactor(voice-codec): delete legacy UdpRtpTransport, migrate 5 callers + 6 tests to FP-3 VoiceUdpTransport (FP-14h-2b, #66)
```

Closes #66.
