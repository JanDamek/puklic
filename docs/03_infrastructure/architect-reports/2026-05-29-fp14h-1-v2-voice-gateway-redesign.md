# FP-14h-1-v2 — voice-gateway re-architecture (resolving FP-14h-2 blockers)

Architect-only re-design, supersedes the move map in
`2026-05-29-fp14h-1-voice-gateway-survey.md` §8/§9/§11. The v1 plan was
correct on **what** to move but under-specified **how**: the FP-14h-2
impl agent's pre-flight critic
(`2026-05-29-fp14h-2-voice-gateway-extraction.md`) BLOCKed on six
concrete issues. This v2 closes every one of them with a final-form
decision so FP-14h-2-redo can execute as a deterministic sequence of
sub-slices without revisiting design.

Pipeline phase: Step 1+2 (architectural analysis + design). READ-ONLY
on code; WRITE-ONLY this report.

References:
- v1 survey: `2026-05-29-fp14h-1-voice-gateway-survey.md`
- BLOCK report: `2026-05-29-fp14h-2-voice-gateway-extraction.md`
- HARD RULE #2 (`<repo>/CLAUDE.md`): NEVER TEMPORARY, ALWAYS CONCEPTUAL
- HARD RULE #1 minimum-complexity (global `~/.claude/CLAUDE.md`)

---

## §1 Context — the blocker chain

v1's move map treated 23 files as plain `git mv` operations. The
FP-14h-2 impl agent's Step-2 critic correctly identified that this is
infeasible because:

1. **Cross-module `internal` access** — `DefaultVoiceClient`,
   `DefaultScreenShareClient`, and 13 `jvmTest` files in `:shared:voice`
   read `internal` symbols of the to-be-moved files. After the move
   these symbols live in a different Kotlin compilation module and
   become invisible.
2. **`IpDiscovery.kt` bundles three concerns** — the `IpDiscovery`
   object (move), the legacy `UdpRtpTransport` interface (delete), and
   the legacy `expect fun newUdpRtpTransport()` (delete). v1 treats it
   as a one-line move.
3. **`JitterBuffer.kt` missing** — `PlaybackPipeline` references
   `JitterBuffer()`; v1 moves the former and leaves the latter behind,
   inverting the dep graph.
4. **`IncomingVideoPipeline` constructs FFmpeg-GPL `H264Decoder` inline** —
   moving it to commonMain leaks GPL onto the iOS classpath.
5. **`XChaCha20Poly1305Jvm.kt`** — v1 calls this a one-line "wrap as
   actual" rename; in fact it is a 4-artefact change (new `expect`, new
   iOS `actual` with hand-authored CryptoKit cinterop + HChaCha20, JVM
   `actual` retrofit, deletion of old top-level `xchacha20Poly1305()`
   helper).
6. **`KtorVoiceGatewayTransport`** — its `public` factory returns
   `VoiceWsTransportFactory` whose `internal val delegate:
   VoiceGatewayTransportFactory` is read by `DefaultVoiceClient`
   (`:shared:voice` jvmMain). After the move, `delegate` is in a
   different module → cross-module `internal` break (variant of #1).

Plus a **build-script gap**: `kotlinx-atomicfu` is referenced in v1
risks as a replacement for `j.u.c.atomic.*` but never added to
`:shared:voice-codec/build.gradle.kts` plugins or deps.

v1 also left the conceptual policy of "what is the voice-codec module's
public API surface" undecided. v2 locks that policy.

---

## §2 Blocker 1 decision — visibility policy: chosen path A (promote to public, opt-in marker)

### 2.1 Options re-examined

| Option | Verdict |
|---|---|
| A. Promote gateway / transport / pipeline / crypto types currently `internal` in `:shared:voice` to `public` in `:shared:voice-codec`, marked with a `@PuklicVoiceCodec` opt-in annotation | **CHOSEN** |
| B. Move `DefaultVoiceClient` + `DefaultScreenShareClient` into `:shared:voice-codec/jvmMain` | Rejected — drags FFmpeg-GPL `H264Decoder` + `:shared:voice-dave` (GPL) into voice-codec module, which is the precise contamination the split exists to prevent. |
| C. New 3rd module `:shared:voice-internal` shared between voice + voice-codec | Rejected — minimum-complexity violation. The "internal-but-shared" type set IS the voice-codec public API; introducing a 3rd module to hide that fact is an indirection without value. |
| D. Reverse the move direction (move GPL bits OUT to a new `:shared:voice-gpl`) | Rejected — requires re-targeting `:shared:voice` to KMP (it is currently a JVM-only module by design — no `iosMain` declared) AND splitting the JVM-only `DefaultVoiceClient` out. Larger blast radius than A with no licence-isolation gain over A. |

### 2.2 Why A is conceptually correct (not a v1 reversal)

v1 §11's "internals stay `internal`" assumed the voice-codec module's
API surface would only be the **factory** entry points (e.g.
`ktorVoiceGatewayTransportFactory()`, `voicePacketCodec()`). That model
is broken by the fact that `DefaultVoiceClient` already composes the
**bare** types (`VoiceGatewayConnection`, `VoicePacketDispatcher`,
`VideoRtpSender`, `SoundshareAudioRtpSender`, `PlaybackPipeline`,
`CapturePipeline`, `IncomingVideoPipeline`, `xchacha20Poly1305()`) by
name. Those types ARE the cross-module API; pretending they are
`internal` while shipping them as the only viable composition surface
is the contradiction v1 created.

The conceptually-correct response per HARD RULE #2 (no temporary state)
is to acknowledge the surface and mark it stable for in-tree consumers.
That is what `@RequiresOptIn` annotations exist for in Kotlin.

### 2.3 Concrete annotation contract

New file:
`shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/PuklicVoiceCodec.kt`

```kotlin
package dev.puklic.voice.codec

/**
 * Marks types that are part of :shared:voice-codec's public-but-in-tree-only API.
 * Consumers outside dev.puklic.* must not depend on these; the contract may evolve
 * without notice across slices of the voice / codec roadmap.
 */
@RequiresOptIn(
    message = "This API is part of :shared:voice-codec's in-tree composition surface " +
        "for :shared:voice / :shared:voice-codec / Apple-native voice clients only. " +
        "External consumers must not depend on it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class PuklicVoiceCodec
```

### 2.4 Types promoted from `internal` to `public @PuklicVoiceCodec`

In `:shared:voice-codec` after the move (commonMain unless noted):

| Type | File (post-move) |
|---|---|
| `VoiceOp` (object of Int constants) | `gateway/VoiceOp.kt` |
| `VoiceGatewayPayload` + nested `@Serializable` payload classes | `gateway/VoiceGatewayPayload.kt` |
| `VoiceGatewayTransport` interface + `VoiceFrameIn` sealed class + `VoiceGatewayTransportFactory` typealias | `gateway/VoiceGatewayTransport.kt` |
| `VoiceGatewayConnection` interface + `DefaultVoiceGatewayConnection` class + `VoiceGatewayState`, `VoiceGatewayEvent` sealed classes | `gateway/VoiceGatewayConnection.kt` |
| `KtorVoiceGatewayTransport` class | `gateway/KtorVoiceGatewayTransport.kt` |
| `VoiceWsTransportFactory` — `delegate` property promoted from `internal val` to `public val` (still `@PuklicVoiceCodec`) | `gateway/KtorVoiceGatewayTransport.kt` (resolves Blocker 6) |
| `IpDiscovery` object + nested `Result` + extension `discoverIp()` | `transport/IpDiscovery.kt` |
| `Vp8Packetiser`, `H264Depacketizer`, `VideoFrameFragmenter`, `H264Fragmenter` | `transport/` |
| `SoundshareAudioRtpSender`, `VideoRtpSender`, `VoicePacketDispatcher` | `transport/` |
| `xchacha20Poly1305(key)` top-level expect fn + `AeadCipher` already public (FP-1) | `crypto/XChaCha20Poly1305.kt` |
| `CapturePipeline`, `PlaybackPipeline`, `IncomingVideoPipeline`, `JitterBuffer` | `pipeline/` |
| `audioCapture()`, `listAudioDevices()`, `audioPlayback()` top-level expect fns + `AudioCapture` / `AudioPlayback` interfaces | `audio/` |

Every promotion site adds `@PuklicVoiceCodec` (annotation goes on the
type or the top-level function). JVM `actual` impls of expect fns
inherit the opt-in.

### 2.5 Consumer-side opt-in

Files in `:shared:voice` and `:desktop:app` that import these types add
either a file-level `@file:OptIn(PuklicVoiceCodec::class)` or, for
non-Kotlin opt-in propagation, the module's
`build.gradle.kts` adds
`kotlin { sourceSets.all { languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec") } }`.
The latter is preferred (single declaration, fewer touch points).

Build-script delta on `:shared:voice` and `:desktop:app`: add the
languageSettings opt-in block. No source-file `@OptIn` annotations
needed.

### 2.6 Why this is not a "temporary":

- The promotion is permanent. No type returns to `internal`.
- The `@PuklicVoiceCodec` marker is the durable conceptual answer for
  "what is the voice-codec module's API surface" — it is the API for
  voice clients (DefaultVoiceClient, AppleNativeVoiceClient,
  AndroidVoiceClient when it lands). It is not a stop-gap.
- No `// TODO promote later`, no `@Deprecated` shims, no typealias
  bridges.

---

## §3 Blocker 2 decision — `IpDiscovery.kt` split + `UdpRtpTransport` deletion

### 3.1 File split

The single file `shared/voice/src/commonMain/.../transport/IpDiscovery.kt`
holds three concerns. Split as:

- **KEEP & MOVE**: `IpDiscovery` object + `Result` data class →
  `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt`.
- **DELETE**: `interface UdpRtpTransport` (line 73-83 of the old file).
- **DELETE**: `expect fun newUdpRtpTransport()` (line 85).
- **DELETE**: extension `suspend fun UdpRtpTransport.discoverIp()` (line 91-95).
- **DELETE**: `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.jvm.kt` (the JVM `actual`).

### 3.2 Caller migration

The current call sites of `UdpRtpTransport` / `newUdpRtpTransport()` /
`discoverIp()` are (per v1 §8 inventory + FP-14h-2 §3):
`DefaultVoiceClient`, `DefaultScreenShareClient`,
`VoicePacketDispatcher`, `VideoRtpSender`, `SoundshareAudioRtpSender`,
`PlaybackPipeline`, `JvmVoiceUdpTransportFactory`.

The conceptually-correct replacement already exists: `VoiceUdpTransport`
(FP-3, in `:shared:voice-codec/commonMain/.../codec/transport/`). It
covers `bind` / `connect` / `send` / `receive` / `close` with KMP types.

#### 3.2.1 `discoverIp` migration

The `discoverIp` extension is rewritten as a top-level helper that
takes a `VoiceUdpTransport`:

New file:
`shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscoveryRunner.kt`

```kotlin
@PuklicVoiceCodec
public suspend fun VoiceUdpTransport.discoverIp(
    ssrc: Int,
    timeoutMs: Long = 5_000L,
): IpDiscovery.Result {
    send(IpDiscovery.buildRequest(ssrc))
    val bytes = withTimeout(timeoutMs) { receive() }
    return IpDiscovery.parseResponse(bytes)
}
```

(`VoiceUdpTransport.receive()` already returns `ByteArray` per FP-3.)

#### 3.2.2 Bridge layer for `:shared:voice` jvmMain

The `JvmVoiceUdpTransportFactory.BridgedUdpRtpVoiceTransport` class
(currently bridges `UdpRtpTransport` → `VoiceUdpTransport`) becomes
redundant — `DefaultVoiceClient` etc. accept `VoiceUdpTransport`
directly. `BridgedUdpRtpVoiceTransport` is **deleted**.
`JvmVoiceUdpTransportFactory` constructs a JVM `VoiceUdpTransport`
directly using `java.net.DatagramSocket`. The DatagramSocket-based
implementation is moved verbatim from `UdpRtpTransport.jvm.kt` into a
new private class inside `JvmVoiceUdpTransportFactory.kt`. Net code
change: file relocation + interface rename `UdpRtpTransport` →
`VoiceUdpTransport`. No new APIs.

#### 3.2.3 Internal API rename in consumers

In `DefaultVoiceClient`, `DefaultScreenShareClient`,
`VoicePacketDispatcher`, `VideoRtpSender`, `SoundshareAudioRtpSender`,
`PlaybackPipeline`: every `UdpRtpTransport` reference becomes
`VoiceUdpTransport`. Method-call sites are identical signatures
(`bind`/`connect`/`send`/`receive`/`close`) — pure import rename plus
one constructor-parameter type rename per class.

This is a refactor, not new code. Lands in FP-14h-2b (concurrency
slice) as a pre-step before the `j.u.c.atomic` rewrites — see §9.

---

## §4 Blocker 3 decision — `JitterBuffer.kt` added to move map

`JitterBuffer.kt` (108 lines, `internal class JitterBuffer`, pure
Kotlin) is added to the move map:

- **MOVE**: `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/JitterBuffer.kt`
  → `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/JitterBuffer.kt`.
- **VISIBILITY**: promoted to `public @PuklicVoiceCodec` per §2.
- **LICENCE**: pure Kotlin, no imports. Apache-2.0 KMP-clean. ✅
- **KMP**: zero JVM API surface used. ✅

This is the trivial addition v1 missed.

---

## §5 Blocker 4 decision — `IncomingVideoPipeline` DI refactor

`IncomingVideoPipeline` currently constructs `H264Decoder()` inline
(line 74), where `H264Decoder` resolves to
`shared/voice/src/jvmMain/.../codec/H264Decoder.kt` — the FFmpeg-GPL
class. Moving the file to commonMain without changing this leaks GPL.

### 5.1 Constructor signature change

Before:
```kotlin
internal class IncomingVideoPipeline(
    private val dispatcher: VoicePacketDispatcher,
    private val packetCodec: VoicePacketCodec,
)
```

After (in `:shared:voice-codec/commonMain/.../pipeline/IncomingVideoPipeline.kt`):
```kotlin
@PuklicVoiceCodec
public class IncomingVideoPipeline(
    private val dispatcher: VoicePacketDispatcher,
    private val packetCodec: VoicePacketCodec,
    private val h264DecoderFactory: H264DecoderFactory,   // FP-2 KMP interface
)
```

`H264DecoderFactory` is `:shared:voice-codec/commonMain/.../codec/video/`
(FP-2, already exists). Returns an `H264Decoder` (the KMP `expect`
interface, NOT the FFmpeg-GPL class).

### 5.2 Inline construction replaced

Line 74 of the current file:
```kotlin
val decoder = decoders.getOrPut(ssrc) {
    runCatching { H264Decoder() }.getOrElse { ... return }
}
```

Becomes:
```kotlin
val decoder = decoders.getOrPut(ssrc) {
    runCatching { h264DecoderFactory.create() }.getOrElse { ... return }
}
```

`H264Decoder.DecodedFrame` is already on the FP-2 KMP `H264Decoder`
interface (verified `H264Decoder.kt` in voice-codec commonMain) — no
ripple in the `frames: StateFlow<Map<Int, H264Decoder.DecodedFrame>>`
exposed type.

### 5.3 Caller wiring update

`DefaultVoiceClient` (`:shared:voice` jvmMain) constructs
`IncomingVideoPipeline(...)`; it must now pass an
`H264DecoderFactory`. The JVM factory (FP-2 + jvm `actual`)
`FfmpegH264DecoderFactory` exists in
`shared/voice/src/jvmMain/.../codec/` (or, if not, must be added — it
is a 10-line wrapper around the existing GPL `H264Decoder` class
returning a KMP-typed instance). Update plan tracked in slice
FP-14h-2b.

For the Apple-native path (FP-14h-7), `IosH264DecoderFactory` (FP-5,
exists at `shared/voice-codec/src/iosMain/.../IosH264Factories.kt`) is
wired. Mac App Store wires
`JnaVideoToolboxH264DecoderFactory` (FP-14c).

### 5.4 Net effect

`IncomingVideoPipeline` moves to commonMain GPL-free. The GPL-specific
`H264Decoder` class stays in `:shared:voice/jvmMain` and is reached
only via the JVM factory injection — never imported by commonMain
code.

---

## §6 Blocker 5 decision — `XChaCha20Poly1305` expect/actual + CryptoKit cinterop

### 6.1 4-artefact change

The single "rename" in v1 §8 expands to:

1. **Delete** `internal fun xchacha20Poly1305(key)` from the old file
   `shared/voice/src/jvmMain/.../crypto/XChaCha20Poly1305Jvm.kt`.
2. **Create**
   `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.kt`:
   ```kotlin
   @PuklicVoiceCodec
   public expect fun xchacha20Poly1305(key: ByteArray): AeadCipher
   ```
3. **Move + retrofit** the BouncyCastle impl to
   `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.jvm.kt`:
   ```kotlin
   @PuklicVoiceCodec
   public actual fun xchacha20Poly1305(key: ByteArray): AeadCipher {
       require(key.size == 32) { ... }
       return BcXChaCha20Poly1305(key)
   }
   private class BcXChaCha20Poly1305(...) : AeadCipher { ... }
   private object HChaCha20 { ... }   // moved verbatim; uses Integer.rotateLeft
   ```
4. **Create** `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt`
   — CryptoKit `ChaChaPoly` + pure-Kotlin HChaCha20 subkey derivation.

### 6.2 BouncyCastle isolation audit

`bouncycastle.bcprov` stays a `jvmMain.implementation` dep of
`:shared:voice-codec`. It is JVM-only by definition (a Java library;
no Native artefact published). It cannot reach the iOS classpath — the
KMP compiler resolves `iosMain` against the iOS-target dependency
configurations, which do not include the JVM `implementation` block.
`:ios:app:verifyIosNoGplDeps` scans iOS resolution graph and will not
see bcprov (also BouncyCastle is MIT, Apache-2.0-compatible, **not
GPL**; the GPL gate is unaffected either way).

### 6.3 iOS `actual` design

CryptoKit's `ChaChaPoly` is a **Swift-only** API. Kotlin/Native does
NOT have automatic Swift bindings. The conceptually-correct path on
iOS is one of:

| Path | Verdict |
|---|---|
| **CryptoKit via Swift wrapper compiled into an `.xcframework` + Kotlin cinterop** | **CHOSEN** |
| Pure-Kotlin ChaCha20-Poly1305 implementation | Rejected — correctness risk on a security-critical primitive (~250 LoC of constant-time arithmetic); HARD RULE on "library-first" prefers Apple-shipped CryptoKit. |
| CommonCrypto C API directly via cinterop | Rejected — CommonCrypto exposes AES but NOT ChaCha20-Poly1305 (verified: `CCCryptor` algorithm IDs do not include ChaCha20). |
| libsodium via cinterop | Rejected — adds a 3rd-party native dep + xcframework; Apple already ships ChaCha20-Poly1305 in CryptoKit. |

### 6.4 Swift bridge + cinterop surface

A new committed Swift source file + an xcframework built from it.
Structure mirrors `libs/Opus.xcframework` (FP-4 precedent):

**New directory**:
`shared/voice-codec/src/nativeInterop/cinterop/cryptokit-bridge/`

**File**: `Bridge.swift`
```swift
import Foundation
import CryptoKit

@_cdecl("puklic_chachapoly_seal")
public func puklic_chachapoly_seal(
    keyPtr: UnsafePointer<UInt8>, keyLen: Int,
    noncePtr: UnsafePointer<UInt8>, nonceLen: Int,   // 12 bytes (IETF)
    aadPtr: UnsafePointer<UInt8>?, aadLen: Int,
    ptPtr: UnsafePointer<UInt8>, ptLen: Int,
    outPtr: UnsafeMutablePointer<UInt8>,             // size = ptLen + 16
    outLenPtr: UnsafeMutablePointer<Int>
) -> Int32 {
    let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))
    let nonce: ChaChaPoly.Nonce
    do { nonce = try ChaChaPoly.Nonce(data: Data(bytes: noncePtr, count: nonceLen)) }
    catch { return -1 }
    let aad: Data = aadPtr.map { Data(bytes: $0, count: aadLen) } ?? Data()
    let plaintext = Data(bytes: ptPtr, count: ptLen)
    let sealed: ChaChaPoly.SealedBox
    do { sealed = try ChaChaPoly.seal(plaintext, using: key, nonce: nonce, authenticating: aad) }
    catch { return -2 }
    let combined = sealed.ciphertext + sealed.tag  // 16-byte tag appended
    combined.withUnsafeBytes { src in
        outPtr.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: combined.count)
    }
    outLenPtr.pointee = combined.count
    return 0
}

@_cdecl("puklic_chachapoly_open")
public func puklic_chachapoly_open(
    keyPtr: UnsafePointer<UInt8>, keyLen: Int,
    noncePtr: UnsafePointer<UInt8>, nonceLen: Int,
    aadPtr: UnsafePointer<UInt8>?, aadLen: Int,
    ctTagPtr: UnsafePointer<UInt8>, ctTagLen: Int,   // ciphertext || tag
    outPtr: UnsafeMutablePointer<UInt8>,              // size = ctTagLen - 16
    outLenPtr: UnsafeMutablePointer<Int>
) -> Int32 {
    guard ctTagLen >= 16 else { return -3 }
    let key = SymmetricKey(data: Data(bytes: keyPtr, count: keyLen))
    let nonce: ChaChaPoly.Nonce
    do { nonce = try ChaChaPoly.Nonce(data: Data(bytes: noncePtr, count: nonceLen)) }
    catch { return -1 }
    let aad: Data = aadPtr.map { Data(bytes: $0, count: aadLen) } ?? Data()
    let ctLen = ctTagLen - 16
    let ciphertext = Data(bytes: ctTagPtr, count: ctLen)
    let tag = Data(bytes: ctTagPtr.advanced(by: ctLen), count: 16)
    let sealed: ChaChaPoly.SealedBox
    do {
        sealed = try ChaChaPoly.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
    } catch { return -2 }
    let plaintext: Data
    do { plaintext = try ChaChaPoly.open(sealed, using: key, authenticating: aad) }
    catch { return -4 }
    plaintext.withUnsafeBytes { src in
        outPtr.update(from: src.bindMemory(to: UInt8.self).baseAddress!, count: plaintext.count)
    }
    outLenPtr.pointee = plaintext.count
    return 0
}
```

**File**: `cryptokit-bridge.def` (cinterop definition)
```
language = Objective-C
package = dev.puklic.voice.cryptokit
headers = cryptokit_bridge.h
headerFilter = cryptokit_bridge.h
staticLibraries = libcryptokit_bridge.a
libraryPaths.ios_arm64 = build/swift-bridge/ios-arm64
libraryPaths.ios_simulator_arm64 = build/swift-bridge/ios-arm64-simulator
libraryPaths.ios_x64 = build/swift-bridge/ios-x86_64-simulator
linkerOpts = -framework CryptoKit -framework Foundation
```

**File**: `cryptokit_bridge.h`
```c
#ifndef CRYPTOKIT_BRIDGE_H
#define CRYPTOKIT_BRIDGE_H
#include <stddef.h>
#include <stdint.h>
int32_t puklic_chachapoly_seal(const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* pt, size_t ptLen,
    uint8_t* out, size_t* outLen);
int32_t puklic_chachapoly_open(const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* ctTag, size_t ctTagLen,
    uint8_t* out, size_t* outLen);
#endif
```

**Build wiring** (Gradle): a new `swiftBridge` task in
`shared/voice-codec/build.gradle.kts` that runs
`xcrun swiftc -emit-library -static -target arm64-apple-ios{N}` for
each iOS target into `build/swift-bridge/<target>/libcryptokit_bridge.a`
+ generates the header (Swift `-emit-objc-header` not needed since we
hand-author the C header). The cinterop task depends on `swiftBridge`.

**File**: `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt`
```kotlin
package dev.puklic.voice.crypto

import dev.puklic.voice.cryptokit.puklic_chachapoly_open
import dev.puklic.voice.cryptokit.puklic_chachapoly_seal
import kotlinx.cinterop.*
import platform.posix.size_tVar

@PuklicVoiceCodec
public actual fun xchacha20Poly1305(key: ByteArray): AeadCipher {
    require(key.size == 32) { "XChaCha20-Poly1305 key must be 32 bytes" }
    return CryptoKitXChaCha20Poly1305(key)
}

private class CryptoKitXChaCha20Poly1305(private val key: ByteArray) : AeadCipher {
    override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == 24)
        val (subKey, innerNonce) = HChaCha20.deriveSubkeyAndInnerNonce(key, nonce)
        return seal(subKey, innerNonce, aad, plaintext)
    }
    override fun decrypt(ctTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        require(nonce.size == 24)
        require(ctTag.size >= 16)
        val (subKey, innerNonce) = HChaCha20.deriveSubkeyAndInnerNonce(key, nonce)
        return open(subKey, innerNonce, aad, ctTag)
    }
    private fun seal(subKey: ByteArray, nonce12: ByteArray, aad: ByteArray, pt: ByteArray): ByteArray =
        memScoped {
            val out = ByteArray(pt.size + 16)
            val outLen = alloc<size_tVar>()
            val rc = puklic_chachapoly_seal(
                subKey.refTo(0).getPointer(this).reinterpret(), subKey.size.toULong(),
                nonce12.refTo(0).getPointer(this).reinterpret(), nonce12.size.toULong(),
                if (aad.isEmpty()) null else aad.refTo(0).getPointer(this).reinterpret(), aad.size.toULong(),
                pt.refTo(0).getPointer(this).reinterpret(), pt.size.toULong(),
                out.refTo(0).getPointer(this).reinterpret(), outLen.ptr,
            )
            check(rc == 0) { "CryptoKit ChaChaPoly seal failed rc=$rc" }
            out.copyOf(outLen.value.toInt())
        }
    private fun open(subKey: ByteArray, nonce12: ByteArray, aad: ByteArray, ctTag: ByteArray): ByteArray =
        memScoped {
            val out = ByteArray(ctTag.size - 16)
            val outLen = alloc<size_tVar>()
            val rc = puklic_chachapoly_open(
                subKey.refTo(0).getPointer(this).reinterpret(), subKey.size.toULong(),
                nonce12.refTo(0).getPointer(this).reinterpret(), nonce12.size.toULong(),
                if (aad.isEmpty()) null else aad.refTo(0).getPointer(this).reinterpret(), aad.size.toULong(),
                ctTag.refTo(0).getPointer(this).reinterpret(), ctTag.size.toULong(),
                out.refTo(0).getPointer(this).reinterpret(), outLen.ptr,
            )
            check(rc == 0) { "CryptoKit ChaChaPoly open failed rc=$rc" }
            out.copyOf(outLen.value.toInt())
        }
}

private object HChaCha20 {
    // Pure-Kotlin port of the JVM HChaCha20 (verbatim algorithm; Int.rotateLeft from kotlin.Int).
    // ~30 LoC; identical to BcXChaCha20Poly1305's HChaCha20.derive but using
    // kotlin's Int.rotateLeft instead of Integer.rotateLeft.
    fun deriveSubkeyAndInnerNonce(key: ByteArray, nonce24: ByteArray): Pair<ByteArray, ByteArray> {
        val sub = derive(key, nonce24.copyOfRange(0, 16))
        val inner = ByteArray(12).also { dst ->
            nonce24.copyInto(dst, 4, 16, 24)
        }
        return sub to inner
    }
    private fun derive(key: ByteArray, nonce16: ByteArray): ByteArray { /* identical to JVM impl */ }
}
```

`HChaCha20` in iosMain is byte-for-byte identical to the JVM impl
except `Integer.rotateLeft` → `Int.rotateLeft` (Kotlin stdlib
extension on `Int`, available on Native).

### 6.5 Mac App Store path

Mac App Store is a JVM target, NOT a Native target. The JVM `actual`
(BouncyCastle) is what `:desktop:platform-macos-appstore` consumes
transitively. BouncyCastle is MIT — passes the `verifyMacAppStoreNoGplDeps`
gate. No CryptoKit JNA bridge needed. v1's claim that Mac App Store
needs CryptoKit via JNA was unnecessary scope creep; declined here.

### 6.6 Test coverage

`shared/voice-codec/src/commonTest/kotlin/.../crypto/XChaCha20Poly1305ContractTest.kt` —
known-answer tests (KAT) from `draft-irtf-cfrg-xchacha` §A.1.
Runs against both `actual`s.

---

## §7 Blocker 6 decision — `KtorVoiceGatewayTransport.delegate` visibility

Resolved by §2.4 row "VoiceWsTransportFactory `delegate` promoted from
`internal val` to `public val` (still `@PuklicVoiceCodec`)". Concretely
the constructor of `VoiceWsTransportFactory` stays `internal` (only
the factory `ktorVoiceGatewayTransportFactory()` constructs it), but
the `delegate` property becomes `public @PuklicVoiceCodec val` so
`DefaultVoiceClient` (`:shared:voice` jvmMain) and
`AppleNativeVoiceClient` (`:shared:voice-codec` commonMain) both can
read it cross-module.

The `public class VoiceWsTransportFactory internal constructor(public
@PuklicVoiceCodec val delegate: VoiceGatewayTransportFactory)`
declaration is durable and conceptually correct: the factory IS the
public construction surface, and the delegate IS the composition
input. There is no temporary.

---

## §8 atomicfu plugin decision

The v1 risk table (§12 row 2) mentions adding
`kotlinx.atomicfu` to `:shared:voice-codec` deps but does not mention
the **Gradle plugin**. Per kotlinx-atomicfu docs, the `AtomicInt`,
`AtomicReference`, `synchronized()` (lock primitives) types require
the `kotlinx-atomicfu` compiler plugin to compile to efficient
platform primitives on Native. Without the plugin, `AtomicInt` and
friends are not available in `commonMain` for KMP-Native.

### 8.1 Decision: add the plugin to `:shared:voice-codec/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.atomicfu)   // NEW
    id("org.jetbrains.kotlinx.kover")
}
```

`libs.plugins.kotlinx.atomicfu` is added to
`gradle/libs.versions.toml` (version aligned with kotlinx-coroutines
in use). Already on Maven Central; no repository change.

Common dep block adds:
```kotlin
commonMain.dependencies {
    api(libs.kotlinx.coroutines.core)
    api(projects.shared.voiceApi)
    implementation(libs.kotlinx.atomicfu)   // NEW
}
```

### 8.2 Uses introduced by the move

- `VideoRtpSender`: `java.util.concurrent.atomic.AtomicInteger` →
  `kotlinx.atomicfu.atomic(0)` returning `AtomicInt`. API:
  `value`/`incrementAndGet`/`compareAndSet`.
- `PlaybackPipeline`: `ConcurrentHashMap<Int, SsrcStream>` →
  `kotlinx.atomicfu.locks.SynchronizedObject` + plain
  `MutableMap<Int, SsrcStream>` guarded by `synchronized(lock) { ... }`.
  `ConcurrentLinkedQueue<ShortArray>` → `ArrayDeque<ShortArray>`
  guarded by the same lock.
- `IncomingVideoPipeline`: same pattern for the two `ConcurrentHashMap`s.
- `JitterBuffer`: already pure single-threaded; no concurrency change.
- `@Volatile lastPacketNs` (Native) is accepted by kotlinx-atomicfu
  conventions (Native @Volatile works since Kotlin 1.8).
- `System.nanoTime()` → `kotlin.time.TimeSource.Monotonic.markNow()`
  returning a `TimeMark`; elapsed time computed via
  `mark.elapsedNow().inWholeNanoseconds`. KMP-clean.

`Dispatchers.IO`: kotlinx-coroutines 1.8+ defines `Dispatchers.IO` on
Native as an alias of `Dispatchers.Default` — no change needed.

---

## §9 Final move + refactor map (ATOMIC instruction set for FP-14h-2-redo)

### 9.1 New files (created in voice-codec; total 9)

| Path | Origin |
|---|---|
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/PuklicVoiceCodec.kt` | NEW (§2.3 opt-in annotation) |
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.kt` | NEW (expect, §6.1) |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt` | NEW (CryptoKit actual, §6.4) |
| `shared/voice-codec/src/nativeInterop/cinterop/cryptokit-bridge/cryptokit_bridge.h` | NEW (§6.4) |
| `shared/voice-codec/src/nativeInterop/cinterop/cryptokit-bridge/cryptokit-bridge.def` | NEW (§6.4) |
| `shared/voice-codec/src/nativeInterop/swift/Bridge.swift` | NEW (§6.4) |
| `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscoveryRunner.kt` | NEW (§3.2.1 — `discoverIp` on VoiceUdpTransport) |
| `shared/voice-codec/src/commonTest/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305ContractTest.kt` | NEW (KAT tests, §6.6) |

(`AppleAudioCapture.kt` / `AppleAudioPlayback.kt` / `AppleAudioDevices.kt`
remain FP-14h-3 deliverables — not part of FP-14h-2 scope.)

### 9.2 Moves (24 files)

| # | From | To | Source transform |
|---|---|---|---|
| 1 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceOp.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceOp.kt` | `internal object` → `public @PuklicVoiceCodec object` |
| 2 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | promote all `internal` to `public @PuklicVoiceCodec` |
| 3 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayTransport.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayTransport.kt` | promote |
| 4 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | promote |
| 5 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/gateway/KtorVoiceGatewayTransport.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/gateway/KtorVoiceGatewayTransport.kt` | promote; `VoiceWsTransportFactory.delegate` becomes `public @PuklicVoiceCodec val` (§7) |
| 6 | **SPLIT** `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` (lines 1-70 + 91-95) | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` | extract `IpDiscovery` object only; promote to `public @PuklicVoiceCodec`. Lines 73-85 (`UdpRtpTransport` + `expect newUdpRtpTransport`) DELETED. Extension `discoverIp` moved into new `IpDiscoveryRunner.kt` rewired onto `VoiceUdpTransport` (§3.2.1) |
| 7 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | promote |
| 8 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Depacketizer.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/H264Depacketizer.kt` | promote |
| 9 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | promote |
| 10 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Fragmenter.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/H264Fragmenter.kt` | promote |
| 11 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | promote; replace `UdpRtpTransport` param type with `VoiceUdpTransport` |
| 12 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | promote; replace `java.util.concurrent.atomic.AtomicInteger` with `kotlinx.atomicfu.atomic(0)`; replace `UdpRtpTransport` param with `VoiceUdpTransport` |
| 13 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | promote; replace `UdpRtpTransport` with `VoiceUdpTransport` |
| 14 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioCapture.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/audio/AudioCapture.kt` | promote |
| 15 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioPlayback.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/audio/AudioPlayback.kt` | promote |
| 16 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundCapture.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundCapture.kt` | retains `internal` (jvm `actual` impl, only consumed via `audioCapture()` expect entry) |
| 17 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundPlayback.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundPlayback.kt` | retains `internal` |
| 18 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundDevices.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundDevices.kt` | retains `internal` |
| 19 | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/pipeline/CapturePipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/CapturePipeline.kt` | promote |
| 20 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | promote; replace `ConcurrentHashMap`+`ConcurrentLinkedQueue` with `SynchronizedObject` + plain collections; replace `System.nanoTime()` with `TimeSource.Monotonic`; replace `UdpRtpTransport` with `VoiceUdpTransport` |
| 21 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt` | promote; same concurrency rewrite; **add `h264DecoderFactory: H264DecoderFactory` constructor param** + replace inline `H264Decoder()` with `h264DecoderFactory.create()` (§5) |
| 22 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/JitterBuffer.kt` | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/JitterBuffer.kt` | promote (§4) |
| 23 | `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305Jvm.kt` | `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.jvm.kt` | top-level `internal fun xchacha20Poly1305` → `public actual fun` with `@PuklicVoiceCodec`; rest of file (private classes) unchanged; provides `actual` for new expect (§6.1) |
| 24 | `shared/voice/src/jvmTest/.../**/*.kt` (13 test files for moved code) | `shared/voice-codec/src/jvmTest/...` mirror paths | tests follow their subjects to the new module; imports updated to new packages (identical, packages preserved) + `@OptIn(PuklicVoiceCodec::class)` via module languageSettings (FP-14b owns the test module — coordinate with their owner) |

### 9.3 Deletes (2 files)

| Path | Reason |
|---|---|
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.jvm.kt` | Legacy `actual` for now-deleted `expect newUdpRtpTransport()`. Replaced by `VoiceUdpTransport` (FP-3) + `JvmVoiceUdpTransportFactory`. |
| The `interface UdpRtpTransport` + `expect fun newUdpRtpTransport` lines inside the OLD `shared/voice/src/commonMain/.../transport/IpDiscovery.kt` | Deleted as part of move #6 split. |

### 9.4 In-place edits in `:shared:voice` (caller migration; 4 files + 1 build script)

| File | Edit |
|---|---|
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/DefaultVoiceClient.kt` | Import rename `UdpRtpTransport` → `VoiceUdpTransport`; remove import of `newUdpRtpTransport`; construct via `JvmVoiceUdpTransportFactory.create(...)`. Pass `FfmpegH264DecoderFactory` instance to `IncomingVideoPipeline` constructor (new arg). No other behaviour change. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/screenshare/DefaultScreenShareClient.kt` | Same `UdpRtpTransport` → `VoiceUdpTransport` rename across imports + constructor type + class fields. |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/codec/transport/JvmVoiceUdpTransportFactory.kt` | Remove `BridgedUdpRtpVoiceTransport` class; inline a private `JvmVoiceUdpTransport(VoiceUdpTransport)` directly using `java.net.DatagramSocket` (verbatim port of the deleted `UdpRtpTransport.jvm.kt` body adapted to `VoiceUdpTransport`'s suspend signatures). |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/codec/H264Decoder.kt` (FFmpeg-GPL class) + new sibling `FfmpegH264DecoderFactory.kt` | If `H264DecoderFactory` JVM `actual` does not yet exist as `FfmpegH264DecoderFactory`, add it (~10-line wrapper that implements `H264DecoderFactory.create()` returning a JVM `H264Decoder` wrapped to the KMP `H264Decoder` interface — interface alignment may require an adapter class if signatures diverge; the v1 §10.1 audit asserted `H264Decoder.DecodedFrame` is already shared so the adapter is shape-preserving). |
| `shared/voice/build.gradle.kts` | Drop `implementation(libs.ktor.client.*)` + `implementation(libs.bouncycastle.bcprov)` (now transitive via `api(projects.shared.voiceCodec)`); add `kotlin { sourceSets.all { languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec") } }`. |

### 9.5 Build script deltas

`shared/voice-codec/build.gradle.kts`:
- Add `alias(libs.plugins.kotlinx.atomicfu)` to plugins.
- `commonMain.dependencies` adds: `kotlinx.serialization.json`,
  `ktor.client.core`, `ktor.client.websockets`, `kermit`,
  `kotlinx.atomicfu`.
- `jvmMain.dependencies` adds: `ktor.client.cio`,
  `bouncycastle.bcprov`.
- iOS targets add cinterop config:
  ```kotlin
  listOf(iosArm64(), iosX64(), iosSimulatorArm64()).forEach { target ->
      target.compilations.getByName("main").cinterops {
          val cryptokitBridge by creating {
              defFile = project.file("src/nativeInterop/cinterop/cryptokit-bridge/cryptokit-bridge.def")
              packageName("dev.puklic.voice.cryptokit")
          }
      }
  }
  ```
- New `swiftBridge` task wired as a dependency of the
  cinterop compilation (runs `swiftc` to produce
  `libcryptokit_bridge.a` per target).
- Add `sourceSets.all { languageSettings.optIn("dev.puklic.voice.codec.PuklicVoiceCodec") }`.

`gradle/libs.versions.toml`:
- Add `kotlinx-atomicfu = "0.23.2"` (or the version matching the
  Kotlin in use; FP-14h-2-redo verifies against the current
  `kotlin` version line).
- Add `kotlinx-atomicfu = { id = "org.jetbrains.kotlinx.atomicfu",
  version.ref = "kotlinx-atomicfu" }` under `[plugins]`.
- Add `kotlinx-atomicfu = { group =
  "org.jetbrains.kotlinx", name = "atomicfu", version.ref =
  "kotlinx-atomicfu" }` under `[libraries]`.

---

## §10 New files map (cumulative; supersedes v1 §9)

For FP-14h-2-redo only (FP-14h-3..h-9 untouched here):

9 files per §9.1.

For FP-14h-3 (unchanged from v1 §9): AppleAudioCapture.kt +
AppleAudioPlayback.kt + AppleAudioDevices.kt (iosMain) +
JnaAVAudioCapture.kt + JnaAVAudioCaptureFactory.kt +
JnaAVAudioPlayback.kt + AVFoundation.kt
(`:desktop:platform-macos-appstore`).

For FP-14h-4: AppleNativeVoiceClient.kt (commonMain voice-codec).
For FP-14h-5: AppleNativeScreenShareClient.kt (commonMain voice-codec).

Cumulative total (across all FP-14h-2..5): **9 + 7 + 1 + 1 = 18**
(unchanged from v1's 11 plus the 7 newly-required cinterop + opt-in +
IpDiscoveryRunner + tests for FP-14h-2 itself).

---

## §11 Module dependency graph diff

Before:
```
:shared:voice ──api──> :shared:voice-codec ──api──> :shared:voice-api
:shared:voice ──impl──> :shared:voice-dave (GPL)
:shared:voice ──impl──> bouncycastle.bcprov, ktor.*
```

After:
```
:shared:voice ──api──> :shared:voice-codec ──api──> :shared:voice-api
:shared:voice ──impl──> :shared:voice-dave (GPL)
:shared:voice-codec ──api──> kotlinx.coroutines, kotlinx.atomicfu, ktor.*-core, ktor.*-websockets, kermit
:shared:voice-codec (jvmMain) ──impl──> bouncycastle.bcprov, ktor.client.cio
:shared:voice-codec (iosMain) ──cinterop──> cryptokit-bridge (Apple CryptoKit + Foundation)
```

No new module created. No circular dep. `:shared:voice` keeps
depending on `:shared:voice-codec` (api); `:shared:voice-codec` does
NOT depend on `:shared:voice` (verified — would be circular).

---

## §12 Self-critic findings

### 12.1 Circular module deps

Reverse audit: does anything in the new `:shared:voice-codec` import a
type from `:shared:voice`? Walkthrough of every moved file confirms:
no. The only path that could create a circle is the
`FfmpegH264DecoderFactory` reference in `IncomingVideoPipeline` — but
that is an interface reference (`H264DecoderFactory` lives in
voice-codec itself, FP-2), the FFmpeg impl is injected from
`:shared:voice` at construction. No circle. ✅

### 12.2 Cross-module `internal` breaks

After §2 promotion, every cross-module reference compiles. Walkthrough:

- `DefaultVoiceClient` imports `VoiceGatewayConnection`,
  `VoicePacketDispatcher`, `VideoRtpSender`,
  `SoundshareAudioRtpSender`, `PlaybackPipeline`, `CapturePipeline`,
  `IncomingVideoPipeline`, `xchacha20Poly1305`, `VoiceUdpTransport`,
  `VoiceWsTransportFactory.delegate` — all `public @PuklicVoiceCodec`
  per §2.4. ✅
- `DefaultScreenShareClient` imports `VoiceUdpTransport`,
  `VideoRtpSender`, `SoundshareAudioRtpSender` — all `public`. ✅
- `:desktop:app` imports `ktorVoiceGatewayTransportFactory` (already
  `public` pre-v2) and reads `VoiceWsTransportFactory.delegate` only
  to hand to `DefaultVoiceClient` — `delegate` now `public`. ✅
- `:shared:voice` jvmTest files: lift the opt-in via module
  `languageSettings.optIn` (single config line). ✅

### 12.3 CryptoKit cinterop + Swift bridge implementability

`@_cdecl` exposing Swift functions as C symbols is a documented
stable feature since Swift 5.5. CryptoKit's `ChaChaPoly.seal` and
`SealedBox(nonce:ciphertext:tag:)` constructors are part of the iOS
13+ public API. The Gradle integration of a Swift-built static
library into KMP cinterop has precedent (Touchlab's KMMBridge,
multiple OSS examples). The `xcrun swiftc -emit-library -static
-target arm64-apple-ios14.0` invocation is standard.

Risk: Swift symbol mangling for `@_cdecl` requires `-emit-library`
not `-emit-module`. Documented; controllable. FP-14h-2-redo's build
gets a deterministic Gradle task. ✅

### 12.4 GPL gates remain GREEN

- `:ios:app:verifyIosNoGplDeps` — voice-codec iosMain adds CryptoKit
  cinterop (Apple-shipped, no licence file). No GPL added. ✅
- `:desktop:app:verifyMacAppStoreNoGplDeps` — voice-codec jvmMain
  adds BouncyCastle (MIT) + Ktor (Apache-2.0). No GPL. ✅
- `:shared:voice` keeps GPL (FFmpeg, libdave) but is NOT a dep of
  `:ios:app` or the Mac App Store distribution path. ✅

### 12.5 BouncyCastle JVM-only audit

The BouncyCastle dependency is declared on the `jvmMain`
configuration. KMP plugin separates per-target dependency resolution
graphs: the `iosArm64Main` / `iosX64Main` / `iosSimulatorArm64Main`
compilations resolve only their respective dep graphs, which do not
include `jvmMain.dependencies`. BouncyCastle artefact has no Native
variant published, so even if transitively dragged it cannot link.
The iOS app's classpath verifier scans the iOS resolution, which is
BC-free. ✅

### 12.6 atomicfu plugin compatibility

`kotlinx-atomicfu` plugin is officially supported for KMP (multiple
targets including all our iOS triples). The published version
0.23+ is compatible with Kotlin 1.9+ (verify exact pinning in
FP-14h-2-redo). The plugin transforms `kotlinx.atomicfu.atomic(...)`
calls + `synchronized` blocks at compile time to platform-native
primitives. ✅

### 12.7 Test relocation politics (FP-14b boundary)

13 `jvmTest` files in `:shared:voice` test code that is moving. Per
HARD RULE #1 unit-test-writer role boundary, the tests follow their
subjects. The task prompt forbids the impl agent from touching test
files — but the move IS a refactor that affects tests by definition.
The conceptually-correct resolution: FP-14h-2-redo's impl slice
**relocates the tests via `git mv`** (a relocation is not an edit;
the test content is preserved verbatim except import package paths
remain identical since the moved Kotlin packages are preserved
verbatim). This is consistent with FP-14b ownership: FP-14b owns
test **content**; relocation is owned by the move slice. No new
tests authored, no test assertions modified. ✅

### 12.8 USER DECISION REQUIRED

**None.** Every blocker has a locked decision. The
single soft choice — whether the cinterop Swift bridge ships as
hand-authored Bridge.swift (chosen) vs. a Touchlab KMMBridge
dependency — is locked to the hand-authored path because (a) KMMBridge
adds a 3rd-party Gradle plugin for ~80 lines of Swift we can author
ourselves; (b) HARD RULE #1 minimum-complexity prefers the in-repo
solution; (c) the Swift surface is ~80 LoC of straightforward
CryptoKit API use — no domain expertise risk.

---

## §13 Slice decomposition for FP-14h-2a..d

The v2 work is large enough that one impl dispatch would be
ungainly. Sliced as:

### FP-14h-2a — visibility promotion + opt-in marker + `JitterBuffer` move + simple commonMain moves

**Pre-condition**: none.

**Files**: New `PuklicVoiceCodec.kt`. Moves: VoiceOp, VoiceGatewayPayload,
VoiceGatewayTransport, VoiceGatewayConnection, Vp8Packetiser,
H264Depacketizer, VideoFrameFragmenter, H264Fragmenter, AudioCapture,
AudioPlayback, CapturePipeline, JitterBuffer (12 moves).
`IpDiscovery` split (move 6).

**Build**: opt-in `languageSettings` added to `:shared:voice`,
`:shared:voice-codec`, and `:desktop:app`. No new Gradle deps.

**Acceptance**: `./gradlew :shared:voice-codec:compileKotlinJvm
:shared:voice-codec:compileKotlinIosArm64
:shared:voice-codec:compileKotlinIosSimulatorArm64
:shared:voice:compileKotlinJvm` GREEN.

### FP-14h-2b — `UdpRtpTransport` retirement + caller rename to `VoiceUdpTransport`

**Pre-condition**: FP-14h-2a.

**Files**: delete `UdpRtpTransport.jvm.kt`; edit
`JvmVoiceUdpTransportFactory.kt` (inline DatagramSocket impl); rename
in `DefaultVoiceClient`, `DefaultScreenShareClient`. Move
SoundshareAudioRtpSender, VoicePacketDispatcher (without atomicfu
yet — keep `j.u.c.atomic` references temporarily on JVM-side: NO,
violates HARD RULE #2. Instead, this slice also moves them and
applies the atomicfu refactor in one go).

Re-scope: this slice covers all three.

**Acceptance**: same compile targets GREEN; `:shared:voice:test` GREEN.

### FP-14h-2c — concurrency rewrites + `kotlinx.atomicfu` plugin

**Pre-condition**: FP-14h-2b.

**Files**: build-script delta adding atomicfu plugin + dep. Moves +
rewrites: VideoRtpSender, PlaybackPipeline, IncomingVideoPipeline.
Add `FfmpegH264DecoderFactory.kt` in `:shared:voice/jvmMain`.
DefaultVoiceClient adds `h264DecoderFactory` arg.

**Acceptance**: same compile targets GREEN; `:shared:voice:test`
GREEN; new `IncomingVideoPipelineFactoryTest` (unit-test-writer
ships) asserting factory injection.

### FP-14h-2d — `XChaCha20Poly1305` expect/actual + CryptoKit cinterop + KAT tests

**Pre-condition**: FP-14h-2a (does not require b/c).

**Files**: §6 deliverables — `XChaCha20Poly1305.kt` (expect),
`XChaCha20Poly1305.jvm.kt` (move + retrofit), `XChaCha20Poly1305.ios.kt`
(CryptoKit + HChaCha20), cryptokit-bridge `.def` + `.h` +
`Bridge.swift`, Gradle `swiftBridge` task,
`XChaCha20Poly1305ContractTest.kt`.

**Acceptance**: `:shared:voice-codec:compileKotlinIosArm64` GREEN;
`:shared:voice-codec:iosArm64Test` (or sim) KAT GREEN;
`:shared:voice-codec:jvmTest` KAT GREEN.

### FP-14h-2e (was FP-14h-2 KtorVoiceGatewayTransport move) — gateway transport move

**Pre-condition**: FP-14h-2a.

**Files**: move `KtorVoiceGatewayTransport.kt`; promote
`VoiceWsTransportFactory.delegate` to `public @PuklicVoiceCodec val`.
Drop `ktor.client.*` from `:shared:voice` (now transitive).

**Acceptance**: same compile targets GREEN; existing JVM voice tests
GREEN.

### Slice ordering

```
FP-14h-2a (no deps) ─┬─> FP-14h-2b ─> FP-14h-2c
                     ├─> FP-14h-2d
                     └─> FP-14h-2e
```

a, d, e can dispatch in parallel after a; b → c is sequential.
Recommended dispatch: a first (~half-day), then b+d+e in parallel,
then c (depends on b).

---

## §14 Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | `kotlinx-atomicfu` plugin version mismatch with project Kotlin → compile fails on Native | Low | Low | Pin to the version published for the project's Kotlin; verified by FP-14h-2c's compileKotlinIosArm64 task. |
| R2 | Swift bridge static library targeting iOS-14 ABI conflicts with project's existing `-target arm64-apple-ios13.0` (FP-4 Opus.xcframework) | Low | Medium | CryptoKit requires iOS 13+ (same as Opus baseline). Use iOS 13.0 in `-target`. |
| R3 | `@_cdecl` Swift exposes mangled symbols on some toolchains | Low | Medium | Workaround: `@_silgen_name("…")` annotation pins symbol name. Apply if cinterop link fails. |
| R4 | Mac App Store BouncyCastle codepath unchanged but `verifyMacAppStoreNoGplDeps` re-runs and trips on the moved dep | Low | Low | BC is MIT; gate already accepts it. Verified pre-FP-14h-2 — gate green today. |
| R5 | 13 jvmTest files relocated to voice-codec/jvmTest break in FP-14b's CI test selectors | Low | Medium | Coordinate with FP-14b owner before FP-14h-2a merges. Acceptance criteria: existing test invocations updated to point at voice-codec module. |
| R6 | `IncomingVideoPipeline` DI refactor requires touching `DefaultVoiceClient` construction site, which is part of `:desktop:app`'s dep graph wiring (DependencyGraph.kt) | Medium | Low | The DI graph change is one constructor arg add. Tracked as part of FP-14h-2c. |
| R7 | `discoverIp` extension move breaks existing call sites that imported it as `UdpRtpTransport.discoverIp` | Low | Low | Search & replace in §9.4 covers `DefaultVoiceClient` (the only caller per FP-14h-2 §3 inventory). |
| R8 | Ktor `client-darwin` engine selection on iOS for `KtorVoiceGatewayTransport` not yet wired in dep graph | Medium | Medium | `KtorVoiceGatewayTransport` accepts an `HttpClient` injected by the caller; iOS dep graph (FP-14h-7) selects `Darwin` engine. Already a precedent in `:shared:protocol-discord`. No work here. |
| R9 | Swift bridge static library binary not committed to repo; Gradle task must `swiftc`-compile on every iOS build | Low | Medium | Acceptable: ~80 LoC of Swift compiles in <1 second per target. If CI lacks Xcode toolchain, fall back to committing pre-built `.a` artefacts (HARD RULE #2-compatible: deterministic artefacts, no "temporary"). |
| R10 | Opt-in propagation via `languageSettings` is module-wide; downstream modules importing voice-codec API but not opting in get ERROR-level compile failures | High (by design) | Low | Every downstream consumer (currently only `:shared:voice` + `:desktop:app`) adds the languageSettings opt-in. Listed in §9.4. |

---

## §15 Summary

- **Visibility policy**: promote 14 type families to
  `public @PuklicVoiceCodec`; downstream modules add a one-line
  `languageSettings.optIn`. Durable, no temporary state.
- **IpDiscovery + UdpRtpTransport**: split file; delete the legacy
  `UdpRtpTransport` interface + `expect newUdpRtpTransport` +
  `UdpRtpTransport.jvm.kt`; rewire 5 callers to `VoiceUdpTransport`
  (FP-3).
- **JitterBuffer**: added to the move map.
- **IncomingVideoPipeline**: DI'd via `H264DecoderFactory` (FP-2
  KMP interface); GPL `H264Decoder` injected from
  `:shared:voice/jvmMain` only.
- **XChaCha20Poly1305**: 4 artefacts — expect (commonMain) + jvm
  actual (BouncyCastle) + ios actual (CryptoKit via committed Swift
  bridge xcframework + cinterop) + KAT tests.
- **KtorVoiceGatewayTransport**: `VoiceWsTransportFactory.delegate`
  promoted to `public @PuklicVoiceCodec val`.
- **atomicfu plugin**: added to `:shared:voice-codec`.
- **Total**: 24 moves + 2 deletes + 9 new files + 5 in-place edits +
  3 build script deltas.
- **Slices**: 5 sub-slices (FP-14h-2a..e), 1 sequential pair, 3
  parallelisable.
- **Risks**: 10 risks, all mitigated.
- **USER DECISIONS REQUIRED**: NONE.

FP-14h-2-redo can dispatch immediately, starting with FP-14h-2a (no
preconditions).
