# FP-3 — VoiceUdpTransport KMP interface + JVM bridge in :shared:voice-codec

**Date**: 2026-05-29
**Slice**: FP-3 of full-feature-parity refactor
**Issue**: #43
**Predecessors**: FP-1 (`8978e6e`), FP-2 (`603f57d`)
**Author**: Claude (architect)
**Status**: Step 2 design — pre-approved blanket per 2026-05-28 macro (non-UX surface)

## 1. Goal

Introduce a KMP-clean UDP transport surface in `:shared:voice-codec` commonMain so the upcoming iOS / macOS `actual` (FP-6, Network.framework) and the existing JVM `DatagramSocket` implementation share one contract:

```kotlin
// commonMain :shared:voice-codec/transport/
public data class Endpoint(val host: String, val port: Int)

public interface VoiceUdpTransport : AutoCloseable {
    public suspend fun send(packet: ByteArray)
    public val incoming: Flow<ByteArray>
    override fun close()
}

public interface VoiceUdpTransportFactory {
    public fun create(remote: Endpoint, localBind: Endpoint?): VoiceUdpTransport
}
```

The JVM side ships a thin adapter `JvmVoiceUdpTransportFactory` that wraps the existing internal `UdpRtpTransport`. No caller of the existing `UdpRtpTransport` (DefaultVoiceClient, DefaultScreenShareClient, PlaybackPipeline, VoicePacketDispatcher, SoundshareAudioRtpSender, VideoRtpSender) is touched.

## 2. Existing JVM surface — discovered signature

`UdpRtpTransport` is an `internal` interface in `:shared:voice` commonMain at
`shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt`:

```kotlin
internal interface UdpRtpTransport {
    suspend fun bind(): Int                              // returns localPort
    suspend fun connect(host: String, port: Int)
    suspend fun send(packet: ByteArray)
    suspend fun receive(): ByteArray                     // blocking pull
    fun close()
}

internal expect fun newUdpRtpTransport(): UdpRtpTransport
```

JVM `actual` (`UdpRtpTransport.jvm.kt`) is a `JvmUdpRtpTransport` backed by a `java.net.DatagramSocket` on `Dispatchers.IO`.

## 3. Decision — `interface` over `expect class`, adapter over identity-implements

### 3.1 Interface, not expect class

Same reasoning as FP-2 §2:

- A plain `interface` in commonMain compiles on every KMP target without forcing an `actual` per platform.
- FP-3 ships no iOS `actual` (that's FP-6, Network.framework); an `expect class` would force a throwing stub today — forbidden by HARD RULE #2.
- Factories carry construction parameters (`remote`, `localBind`); the transport interface itself stays argument-free.

### 3.2 Adapter, not identity-implements — signatures DIVERGE

The architect-report sketch (`2026-05-29-full-feature-parity.md` §3.2) prescribes the public interface shape:

| Sketch (public, this slice) | Existing JVM `UdpRtpTransport` |
|---|---|
| `suspend fun send(packet)` | `suspend fun send(packet)` |
| `val incoming: Flow<ByteArray>` | `suspend fun receive(): ByteArray` (pull) |
| constructed with `(remote, localBind)` via factory | open lifecycle `bind() → connect() → send/receive` |
| `close()` | `close()` |

The two are structurally different: existing is pull-based with a deferred bind/connect; the new public contract is push (`send`) + Flow (`incoming`) created in a single factory call.

The brief permits "prefer the existing shape" if signatures diverge — but the existing shape is `internal` to `:shared:voice` and pull-based. Pull-style transport does not fit iOS Network.framework (`NWConnection` delivers received datagrams via a callback that naturally fits a Flow producer). Locking commonMain into the pull shape would force the iOS `actual` to bridge callback → suspending-pull with an unbounded channel internally anyway — adds the same adapter, just on the other side.

Decision: **keep the architect-report's Flow/factory shape in commonMain.** Bridge on JVM via a new internal adapter `BridgedUdpRtpVoiceTransport` that:

1. Constructs the existing `UdpRtpTransport` via `newUdpRtpTransport()`.
2. Performs `bind()` (binds to ephemeral local port — `localBind` is currently ignored, see §3.3) and `connect(remote.host, remote.port)` synchronously inside the factory `create(...)` suspending? No — `create` is non-suspending by contract. So bind/connect happen lazily on first `send` or on first `incoming` collect, behind a `kotlinx.coroutines.sync.Mutex` (one-shot init). Acceptable: existing JVM voice client already calls bind/connect once, this just defers them by a handful of nanoseconds.
3. Converts `receive()` loop into a `Flow<ByteArray>` (cold flow built with `flow { while (active) emit(receive()) }`).
4. `close()` delegates.

### 3.3 `localBind` parameter — passthrough today, real binding tomorrow

The architect report's `localBind: Endpoint?` parameter exists to let iOS Network.framework bind to a specific local interface (multi-interface phones). The current JVM impl always binds to `0.0.0.0:0`. For FP-3 we accept `localBind` in the factory signature, but the JVM bridge **ignores** it (existing behaviour). This is not a temporary stub: the JVM behaviour is correct ("bind to any interface, ephemeral port") and matches current production. A future JVM caller wanting a specific local bind would extend the JVM `UdpRtpTransport` interface — orthogonal to FP-3.

Documented in the KDoc on `VoiceUdpTransportFactory.create`.

### 3.4 No caller migration in FP-3

`DefaultVoiceClient` / `DefaultScreenShareClient` / `PlaybackPipeline` continue to use `internal UdpRtpTransport` directly. FP-3 only **adds** the public interface seam. Migrating callers belongs to a later slice once both the JVM bridge and an iOS `actual` exist and the two can be selected by `DependencyGraph`. Doing it now would touch 6+ files for zero current user-visible behaviour — violates minimum-complexity.

## 4. Files touched

| Change | Path |
|---|---|
| New | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/transport/Endpoint.kt` |
| New | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/transport/VoiceUdpTransport.kt` |
| New | `shared/voice-codec/src/commonTest/kotlin/dev/puklic/voice/codec/transport/VoiceUdpTransportContractTest.kt` |
| New | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/codec/transport/JvmVoiceUdpTransportFactory.kt` |
| Update (comment) | `shared/voice-codec/build.gradle.kts` header note |

`:shared:voice-codec/build.gradle.kts` already declares `kotlinx.coroutines.core` via the `puklic.kmp-library` convention (used by existing `VoicePacketCodec`); no new dependency needed.

`:shared:voice/build.gradle.kts` — no change. The new bridge file lives in jvmMain and uses already-present `:shared:voice-codec` dep + existing internal `UdpRtpTransport`.

## 5. Self-critic (Step 3)

- **`localBind` ignored on JVM** — flagged as not-a-stub above. Documented. The parameter exists in the contract because iOS needs it; JVM behaviour is correct without it.
- **Lazy bind/connect inside the bridge** — moves work from caller-controlled bind/connect points to the first send/receive. The semantics are identical because no current JVM caller delays bind/connect; they always happen before any traffic. Adapter cost: one `Mutex.withLock` on first send and first incoming collect. Negligible vs 50 packets/sec voice cadence.
- **`incoming` is a cold Flow** — each `collect` would start its own receive loop, but the underlying `DatagramSocket` is single-receiver. Mitigation: the cold flow performs the same one-shot init mutex check; if a second collector subscribed, the existing receive loop would compete. Acceptable for FP-3 because there is **no caller** that collects `incoming` yet. A future caller pattern (single-collector) matches voice/screenshare today. If multi-collector is ever needed, wrap in `shareIn` at the call site — not in the transport.
- **No iOS `actual`** — explicit. FP-6 owns Network.framework. iOS targets compile because plain interfaces resolve from Kotlin metadata only.
- **Internal type leak risk** — `JvmVoiceUdpTransportFactory` calls `newUdpRtpTransport()` which is `internal` to `:shared:voice`. Same module → same internal visibility. OK.
- **Naming** — `VoiceUdpTransport` (public, KMP) vs existing internal `UdpRtpTransport`. Distinct enough; no overload confusion because they live in different packages (`dev.puklic.voice.codec.transport` vs `dev.puklic.voice.transport`).

## 6. Acceptance gates (Step 6)

- `./gradlew :shared:voice-codec:build` green
- `./gradlew :shared:voice-codec:compileKotlinIosArm64` green
- `./gradlew :shared:voice-codec:compileKotlinIosX64` green
- `./gradlew :shared:voice-codec:compileKotlinIosSimulatorArm64` green
- `./gradlew :shared:voice:build` green
- `./gradlew :ios:app:verifyIosNoGplDeps` green (interfaces stay Apache-2.0 / pure-Kotlin)

## 7. Out of scope

- iOS / macOS `actual` (FP-6, Network.framework)
- Migrating existing JVM callers to the new public interface
- A `bind(): Int` analogue on the public surface (current JVM caller uses bind's returned local port for IP discovery; no iOS caller needs this; cross-platform IP discovery is a separate concern)
- Multi-collector `incoming` semantics
- Real `localBind` honouring on JVM
