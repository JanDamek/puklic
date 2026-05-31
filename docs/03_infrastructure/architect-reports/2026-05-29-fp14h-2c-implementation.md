# FP-14h-2c — XChaCha20Poly1305 expect/actual + iOS CryptoKit cinterop (impl report)

Implementation-role plan for issue #67. Executes §6 of the v2 redesign
(`2026-05-29-fp14h-1-v2-voice-gateway-redesign.md`) — referred to there
as slice "FP-14h-2d" but tracked here as the FP-14h-2c work item per
issue #67 / user task label. Sliced as four artefacts:

1. `expect class xchacha20Poly1305()` in `:shared:voice-codec/commonMain`
2. JVM `actual` (BouncyCastle, retrofitted from the existing
   `:shared:voice/jvmMain/.../XChaCha20Poly1305Jvm.kt`)
3. iOS `actual` (Apple CryptoKit `ChaChaPoly` via committed Swift
   bridge + Kotlin/Native cinterop + pure-Kotlin/Native HChaCha20)
4. KAT contract tests in `commonTest`

References:
- v2 redesign §6
- Issue #67 (FP-14h-2c)
- Slice predecessor: `FP-14h-2a` `618c1f7` (PuklicVoiceCodec annotation
  already shipped), `FP-14h-2b` `2510920` (legacy UdpRtpTransport deleted).
- HARD RULE #2: no temporary state.

---

## §1 Expect signature (commonMain)

`shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.kt`

```kotlin
package dev.puklic.voice.crypto

import dev.puklic.voice.codec.PuklicVoiceCodec

/**
 * XChaCha20-Poly1305 AEAD per draft-irtf-cfrg-xchacha (used by Discord
 * `aead_xchacha20_poly1305_rtpsize`).
 *
 *  - Key:   32 bytes
 *  - Nonce: 24 bytes
 *  - Tag:   16 bytes, appended to ciphertext on encrypt; verified+stripped on decrypt
 *
 * The returned [AeadCipher] is stateless w.r.t. nonce sequencing; the caller
 * advances the 24-byte nonce per packet (NonceGenerator).
 */
@PuklicVoiceCodec
public expect fun xchacha20Poly1305(key: ByteArray): AeadCipher
```

The existing top-level helper in `:shared:voice/jvmMain` is the seed.
The expect is intentionally a top-level `fun`, not a class, because the
existing call sites in `DefaultVoiceClient`, `VoiceRtpSender`, etc.
already use the function form and the architect plan keeps that
contract.

## §2 JVM actual (BouncyCastle)

`shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.jvm.kt`

Content: the existing `XChaCha20Poly1305Jvm.kt` body, retrofitted:
- File header / package preserved as `dev.puklic.voice.crypto`
- `internal fun xchacha20Poly1305(key)` → `@PuklicVoiceCodec public actual fun xchacha20Poly1305(key)`
- `private class BcXChaCha20Poly1305` + `private object HChaCha20`
  retained verbatim (Apache-2.0 KMP-clean; `Integer.rotateLeft` is fine
  on JVM target).
- Old file at `:shared:voice/jvmMain/.../XChaCha20Poly1305Jvm.kt` is
  **deleted** in the same commit so there is no duplicate symbol.

BouncyCastle is already on `:shared:voice/jvmMain.implementation`. The
new actual moves to `:shared:voice-codec/jvmMain`, so the dependency
moves with it: `:shared:voice-codec/build.gradle.kts` jvmMain block
gains `implementation(libs.bouncycastle.bcprov)`; the line stays in
`:shared:voice/jvmMain.implementation` because BC is still used
transitively (no — actually, after this move, `:shared:voice` no longer
needs BC directly, since the cipher comes via `:shared:voice-codec`'s
exported `xchacha20Poly1305()`. But `:shared:voice` retains its other
crypto code? Audit: no, no other BC usage in `:shared:voice` —
`XChaCha20Poly1305Jvm.kt` is the only file importing
`org.bouncycastle.*`. Therefore the BC dep moves from `:shared:voice`
to `:shared:voice-codec`).

Result: `:shared:voice/build.gradle.kts` jvmMain drops
`implementation(libs.bouncycastle.bcprov)`. `:shared:voice-codec/build.gradle.kts`
jvmMain adds it.

## §3 iOS actual (CryptoKit cinterop)

Apple's CryptoKit is Swift-only. The Kotlin/Native path is a committed
Swift bridge compiled to a static library (`libpuklic_crypto.a`) per
iOS slice, linked via a hand-authored `.def` cinterop descriptor.

### §3.1 Swift bridge

`shared/voice-codec/src/iosMain/native/PuklicCryptoBridge/Bridge.swift`

Exposes two C symbols via `@_cdecl`:

```swift
import Foundation
import CryptoKit

@_cdecl("puklic_chachapoly_seal")
public func puklic_chachapoly_seal(
    keyPtr: UnsafePointer<UInt8>, keyLen: Int,
    noncePtr: UnsafePointer<UInt8>, nonceLen: Int,     // expect 12 (IETF)
    aadPtr: UnsafePointer<UInt8>?, aadLen: Int,
    ptPtr: UnsafePointer<UInt8>?, ptLen: Int,
    outPtr: UnsafeMutablePointer<UInt8>,               // capacity ptLen + 16
    outLenPtr: UnsafeMutablePointer<Int>
) -> Int32 { /* … */ }

@_cdecl("puklic_chachapoly_open")
public func puklic_chachapoly_open(/* mirror */) -> Int32 { /* … */ }
```

Both wrap `ChaChaPoly.seal(_:using:nonce:authenticating:)` and
`ChaChaPoly.open(_:using:authenticating:)` (iOS 13+). The seal output
is `sealed.ciphertext || sealed.tag` (16-byte tag appended) matching
the JVM BouncyCastle output layout.

Return codes: `0` success; negative on nonce-init failure / seal failure
/ tag-verify failure. The Kotlin actual translates non-zero into
`IllegalStateException` (encrypt) or `IllegalStateException` with
"authentication failed" message (decrypt) so tests that
`shouldThrow<Exception>` still match.

### §3.2 cinterop descriptor

`shared/voice-codec/src/iosMain/native/PuklicCryptoBridge/puklic_crypto.def`

```
language = C
package = dev.puklic.voice.crypto.cryptokit
headers = puklic_crypto.h
staticLibraries = libpuklic_crypto.a
```

`compilerOpts` and `extraOpts -libraryPath` per-target are injected
from `build.gradle.kts` (mirrors `libopus.def` pattern).

`puklic_crypto.h`:

```c
#ifndef PUKLIC_CRYPTO_H
#define PUKLIC_CRYPTO_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

int32_t puklic_chachapoly_seal(
    const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* pt, size_t ptLen,
    uint8_t* out, size_t* outLen);

int32_t puklic_chachapoly_open(
    const uint8_t* key, size_t keyLen,
    const uint8_t* nonce, size_t nonceLen,
    const uint8_t* aad, size_t aadLen,
    const uint8_t* ctTag, size_t ctTagLen,
    uint8_t* out, size_t* outLen);

#ifdef __cplusplus
}
#endif
#endif
```

### §3.3 Gradle wiring (`swiftCompilePuklicCrypto*` per target)

A new Gradle task per iOS triple compiles `Bridge.swift` to a static
library:

```
xcrun -sdk <sdk> swiftc \
    -emit-library -static \
    -target <arch>-apple-ios14.0[-simulator] \
    -parse-as-library \
    -O \
    -module-name PuklicCryptoBridge \
    -o build/swift-bridge/<slice>/libpuklic_crypto.a \
    Bridge.swift
```

The cinterop compilation depends on the corresponding swiftCompile
task. Output slice paths:

| target              | swift target string                     | output dir                                      |
|---------------------|-----------------------------------------|-------------------------------------------------|
| iosArm64            | arm64-apple-ios14.0                     | build/swift-bridge/ios-arm64/                  |
| iosSimulatorArm64   | arm64-apple-ios14.0-simulator           | build/swift-bridge/ios-arm64-simulator/        |
| iosX64              | x86_64-apple-ios14.0-simulator          | build/swift-bridge/ios-x86_64-simulator/       |

`linkerOpts = -framework CryptoKit -framework Foundation` in the .def
ensures the Apple frameworks are linked into the final `.kexe` /
framework.

iOS deployment target is `14.0` (matches `gradle/libs.versions.toml`
`ios-deployment-target = "14.0"`); CryptoKit needs ≥ 13.0. ✅

### §3.4 Kotlin/Native actual

`shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt`

```kotlin
@PuklicVoiceCodec
public actual fun xchacha20Poly1305(key: ByteArray): AeadCipher {
    require(key.size == 32)
    return CryptoKitXChaCha20Poly1305(key.copyOf())
}

private class CryptoKitXChaCha20Poly1305(private val key: ByteArray) : AeadCipher {
    override fun encrypt(plaintext: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray { … }
    override fun decrypt(ciphertextWithTag: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray { … }
}
```

The `encrypt` path:

1. `HChaCha20.derive(key, nonce[0..16])` → 32-byte subkey
2. `innerNonce` = `byteArrayOf(0,0,0,0) || nonce[16..24]` (12 bytes)
3. `puklic_chachapoly_seal(subkey, innerNonce, aad, plaintext, out, outLen)`
4. return `out.copyOf(outLen)` — ciphertext || tag

The `HChaCha20` helper is byte-for-byte identical to the JVM impl
except `Integer.rotateLeft` → Kotlin stdlib `Int.rotateLeft`
extension (available on Native).

`decrypt` mirrors using `puklic_chachapoly_open`; on `rc != 0` throws
`IllegalStateException("XChaCha20-Poly1305 decrypt failed (rc=$rc)")`
matching the BC `InvalidCipherTextException` thrown surface.

## §4 KAT contract tests (commonTest)

`shared/voice-codec/src/commonTest/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305ContractTest.kt`

Tests in commonTest so the same KAT runs against both jvm + ios actuals.

### §4.1 Test vector source

`draft-arciszewski-xchacha-03 §A.3.1` — the canonical XChaCha20-Poly1305
KAT (the reference vector cited by RFC drafts and libsodium). It
provides:

- key (32 bytes)
- nonce (24 bytes)
- aad (12 bytes)
- plaintext (~ 114 bytes, "Ladies and Gentlemen of the class of '99…")
- expected ciphertext + tag (130 bytes total)

This vector is also reproduced in the libsodium test suite and
NaCl's test vectors, providing a strong cross-implementation
interop anchor.

### §4.2 Tests

1. **`encrypt produces expected KAT ciphertext`** — encrypt the
   plaintext with the KAT key+nonce+aad; assert exact byte equality
   with the expected ciphertext.
2. **`decrypt of KAT ciphertext returns expected plaintext`** — round-trip the canonical vector backwards.
3. **`round-trip random returns plaintext`** — fuzz against a randomly
   generated input (deterministic seeds so failures are repro).
4. **`decrypt with tampered tag throws`**.
5. **`decrypt with wrong key throws`**.
6. **`decrypt with wrong aad throws`**.
7. **`key of wrong length is rejected`** — `IllegalArgumentException`.
8. **`nonce of wrong length is rejected`** — `IllegalArgumentException`.

Tests use `kotlin.test` + `io.kotest.matchers.shouldBe` /
`io.kotest.assertions.throwables.shouldThrow` (already on
`commonTest.dependencies` per `:shared:voice-codec/build.gradle.kts`).

### §4.3 commonTest dep audit

The existing `commonTest.dependencies` block has `kotlin("test")` +
`kotest.assertions.core`. No new dep needed.

## §5 Existing JVM tests in :shared:voice

`shared/voice/src/jvmTest/.../XChaCha20Poly1305Test.kt` (the round-trip
+ tamper + wrong-key + wrong-aad + wrong-key-length tests, 67 LoC)
currently imports `dev.puklic.voice.crypto.xchacha20Poly1305` from
the same module. After the move, `xchacha20Poly1305` lives in
`:shared:voice-codec` but is still re-exported transitively via
`:shared:voice → api(:shared:voice-codec)`, so the imports continue to
resolve **without changes**. Tests stay in their current location
(this slice is FP-14h-2c, not FP-14h-2-redo full test relocation).

The new KAT tests in `commonTest` are additive; they do NOT replace
the existing JVM tests. The existing tests verify the JVM impl;
the KAT tests verify cross-actual interop.

## §6 Self-critic findings (Step 3)

### §6.1 Risk: Swift toolchain availability for CI

The Gradle task invokes `xcrun swiftc`. CI environments without Xcode
toolchain (notably Linux GHA runners) would fail. Mitigation: the
swift-bridge compile task is registered only on macOS hosts (via
`if (HostManager.hostIsMac) { … }`) and gated by the iOS target
compilation — which already only runs on macOS in this repo. ✅

### §6.2 Risk: Symbol mangling for `@_cdecl`

`@_cdecl` is a documented Swift attribute since Swift 5.5 that exports
a C-callable symbol with no mangling. `-emit-library -static` writes
the symbol unmangled into the static library. cinterop reads the C
header and resolves at link time. If a future Swift version regresses
this, fallback to `@_silgen_name("puklic_chachapoly_seal")` per R3 in
v2 §14.

### §6.3 Risk: HChaCha20 KAT-tested independently?

The KAT vector tests the *composed* operation (HChaCha20 + ChaCha20-Poly1305)
end-to-end, which is the only interface we expose. No need to KAT
HChaCha20 in isolation — if the composition produces the expected
ciphertext, HChaCha20 is implicitly correct (any HChaCha20 bug
propagates to subkey, which propagates to ciphertext bytes).

### §6.4 Risk: BouncyCastle MIT licence

BC is MIT, not GPL — `verifyMacAppStoreNoGplDeps` accepts it
(verified: dep already on the Mac App Store path via current
`:shared:voice` jvmMain). Moving to `:shared:voice-codec` does not
change the resolved Mac App Store classpath licence-wise. ✅

### §6.5 Risk: `verifyIosNoGplDeps` for the Swift bridge

The new Swift code links `CryptoKit` + `Foundation` (Apple-shipped
frameworks, no licence file). No GPL added. The static library lives
in the iOS Kotlin/Native compilation, not in the JVM classpath, so
the JVM-based GPL scanner does not see it. ✅

### §6.6 Risk: copyOf(key) on Native

Defensive copy of the key in the iOS actual prevents caller mutation
from invalidating cached ChaChaPoly subkeys. Adds 32 bytes per
cipher instance; negligible. ✅

### §6.7 Risk: nonce-12 byte layout

The Discord scheme is XChaCha20-Poly1305 *_rtpsize* — the 24-byte nonce
is composed of the RTP-derived counter (16 bytes) || 8 zero bytes (in
practice). The HChaCha20 derivation uses the first 16 bytes as the
subkey-derivation nonce, the inner 12-byte ChaCha20-Poly1305 nonce is
`zero[4] || nonce[16..24]`. This matches the JVM impl byte-for-byte
(verified by reading the existing JVM file lines 60-66). The Swift
bridge is given the already-derived 32-byte subkey + the 12-byte inner
nonce — it does NOT see the original 24-byte nonce. KAT vectors
operate on the same convention. ✅

### §6.8 Risk: HARD RULE #2 — temporary state

No TODO, no `@Deprecated`, no fallback shim, no half-built switch. The
JVM actual replaces the old `:shared:voice/jvmMain` location entirely
(old file deleted, not preserved with `@Deprecated`). The iOS actual
is the conceptual answer (Apple-shipped CryptoKit). KAT tests are
permanent contract tests. ✅

## §7 Files (cumulative)

### Added (8)

- `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.kt` (expect)
- `shared/voice-codec/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.jvm.kt` (jvm actual, moved + retrofitted)
- `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305.ios.kt` (iOS actual)
- `shared/voice-codec/src/iosMain/native/PuklicCryptoBridge/Bridge.swift`
- `shared/voice-codec/src/iosMain/native/PuklicCryptoBridge/puklic_crypto.def`
- `shared/voice-codec/src/iosMain/native/PuklicCryptoBridge/puklic_crypto.h`
- `shared/voice-codec/src/commonTest/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305ContractTest.kt` (KAT)
- This report.

### Deleted (1)

- `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305Jvm.kt`

### Modified (2)

- `shared/voice-codec/build.gradle.kts` — adds `bouncycastle.bcprov` to jvmMain; adds `puklic_crypto` cinterop to each iOS target; adds `swiftCompilePuklicCryptoBridge*` task per target; new commonMain crypto source set already exists.
- `shared/voice/build.gradle.kts` — drops `implementation(libs.bouncycastle.bcprov)` from jvmMain.
