# FP-1 — Extract voice transport codec to `:shared:voice-codec` (Apache-2.0, KMP)

**Date**: 2026-05-29
**Author**: pipeline orchestrator (this session)
**Issue**: #41
**Master design**: `2026-05-29-full-feature-parity.md` §3.1, §3.4, §7

## 1. Goal

Seed `:shared:voice-codec` (Apache-2.0 KMP, jvm + android + iosArm64 + iosX64 +
iosSimulatorArm64) with the pure-Kotlin Discord voice transport AEAD glue so
that the App Store iOS / macOS builds (future FP-4..6) can share the same
packet codec / nonce sequencing / RTP framing code path with the GPL desktop
build, without dragging in `:shared:voice`'s BouncyCastle / FFmpeg / libdave.

## 2. Reuse audit (Step 1)

Existing reusable types (no rewrite):

| File | Current location | Pure-Kotlin? | Action |
|---|---|---|---|
| `crypto/AeadCipher.kt` | `:shared:voice/commonMain` | yes (interface only; the `expect fun xchacha20Poly1305` is JVM-bound) | Move the **interface** to `:shared:voice-codec/commonMain`. Drop the `expect fun` from the moved file — keep it in `:shared:voice/jvmMain` next to `XChaCha20Poly1305Jvm.kt` as a JVM-only factory function. App Store builds will provide their own `AeadCipher` impls via CryptoKit cinterop in a later slice; the interface is the contract. |
| `crypto/NonceGenerator.kt` | `:shared:voice/jvmMain` | uses `java.util.concurrent.atomic.AtomicInteger` (JVM-only) | Move to `:shared:voice-codec/commonMain`. Replace `AtomicInteger` with `kotlin.concurrent.atomics.AtomicInt` (stdlib, KMP, stable since Kotlin 2.1.20; repo is on 2.1.21). |
| `transport/RtpPacket.kt` | `:shared:voice/commonMain` | yes | Move to `:shared:voice-codec/commonMain`. `VoicePacketCodec` depends on it. |
| `transport/VoicePacketCodec.kt` | `:shared:voice/jvmMain` | uses `java.util.concurrent.atomic.AtomicInteger` + `System.arraycopy` | Move to `:shared:voice-codec/commonMain`. Replace `AtomicInteger` with `kotlin.concurrent.atomics.AtomicInt`. Replace `System.arraycopy` with `copyInto` (Kotlin stdlib, KMP). |
| `crypto/XChaCha20Poly1305Jvm.kt` | `:shared:voice/jvmMain` | BouncyCastle JVM-only | **Stays** in `:shared:voice/jvmMain`. App Store builds will not link this file. FP-4 introduces CryptoKit `AeadCipher` impl for iOS. |

All JVM call sites (`DefaultVoiceClient`, `SoundshareAudioRtpSender`,
`VideoRtpSender`, `IncomingVideoPipeline`, `PlaybackPipeline`,
`DefaultScreenShareClient`) continue to resolve the same FQNs:
`dev.puklic.voice.crypto.AeadCipher`, `NonceGenerator`,
`dev.puklic.voice.transport.RtpPacket`, `VoicePacketCodec`. Package names
unchanged — only Gradle module ownership changes. `:shared:voice` adds
`api(projects.shared.voiceCodec)` to keep transitive resolution intact.

## 3. JVM-only-dep audit

Confirmed JVM-only references in the four moving files:

```
NonceGenerator.kt           java.util.concurrent.atomic.AtomicInteger
VoicePacketCodec.kt         java.util.concurrent.atomic.AtomicInteger
VoicePacketCodec.kt         java.lang.System.arraycopy
AeadCipher.kt               internal expect fun xchacha20Poly1305  (jvm-only actual exists)
RtpPacket.kt                none
```

Resolution per file (conceptually correct, no temporary shims):

- `AtomicInteger` → `kotlin.concurrent.atomics.AtomicInt` (KMP stdlib). Same
  semantics: `getAndIncrement`, `getAndAdd`, `get`. Requires opt-in
  `@OptIn(ExperimentalAtomicApi::class)` until Kotlin 2.2 promotes it to
  stable; this matches Kotlin team's stability roadmap and is not a temporary
  workaround — the API is the canonical KMP atomic.
- `System.arraycopy(src, sOff, dst, dOff, len)` → `src.copyInto(dst, dOff,
  sOff, sOff + len)` (Kotlin stdlib).
- `expect fun xchacha20Poly1305` — **does not move**. Stays in
  `:shared:voice` jvmMain as a JVM-only top-level factory function. The
  `AeadCipher` interface itself moves; callers that need a concrete instance
  call `xchacha20Poly1305(...)` from `:shared:voice` jvmMain as today. iOS
  callers in future FPs will construct CryptoKit-backed `AeadCipher`
  instances.

No file has irreducible JVM dependencies. The move is clean — no blocker.

## 4. Module skeleton

`shared/voice-codec/build.gradle.kts`:

```kotlin
// :shared:voice-codec — Apache-2.0 pure-Kotlin Discord voice transport codec.
//
// Apache-2.0 + KMP-wide (jvm + android + iOS) so that future App Store iOS /
// macOS builds can share the AEAD packet framing / nonce sequencing / RTP
// header code path with the GPL desktop build without pulling in
// :shared:voice's BouncyCastle / FFmpeg-GPL / libdave.
//
// Contains:
//   - AeadCipher interface (the pluggable cipher contract)
//   - NonceGenerator (24-byte XChaCha20 nonce counter, _rtpsize layout)
//   - RtpPacket (12-byte RTP header read/write)
//   - VoicePacketCodec (encode/decode RTP + AEAD glue)
//
// Does NOT contain a concrete AeadCipher impl. JVM impl lives in
// :shared:voice/jvmMain (BouncyCastle); iOS impl will land in FP-4..6
// (CryptoKit cinterop).
//
// See docs/03_infrastructure/architect-reports/2026-05-29-fp1-voice-codec-extraction.md
// See docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md §3.1
// See docs/03_infrastructure/dep-policy.md

plugins {
    id("puklic.kmp-library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotest.assertions.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotest.runner.junit5)
        }
    }
}

android {
    namespace = "dev.puklic.shared.voicecodec"
}
```

## 5. Gradle wiring diff

`settings.gradle.kts`: add `include(":shared:voice-codec")` alphabetically
above `:shared:voice`.

`shared/voice/build.gradle.kts`:
```
commonMain.dependencies {
    api(projects.shared.voiceApi)
+   api(projects.shared.voiceCodec)
    ...
}
```
`api` (not `implementation`) so that existing JVM consumers
(`:desktop:app`, voice's own jvmMain, voice's jvmTest) see the moved types
transitively without import churn.

## 6. Test plan

The four files have existing JVM-side tests:

- `VoicePacketCodecTest` — moves to `:shared:voice-codec/commonTest` (uses
  `xchacha20Poly1305` factory which stays in `:shared:voice` jvmMain → the
  cipher-using tests need to stay in `:shared:voice/jvmTest`). Solution:
  keep `VoicePacketCodecTest` as `jvmTest` in `:shared:voice` (it needs the
  JVM cipher factory), and add a NEW `commonTest` test in
  `:shared:voice-codec` that exercises `VoicePacketCodec` against a tiny
  in-test `FakeAeadCipher` (records calls, returns plaintext as ciphertext
  with a 16-byte zero tag). This satisfies issue #41 acceptance ("1 unit test
  in :shared:voice-codec commonTest that verifies a round-trip encode+decode
  (you can mock the cipher inputs)").
- `XChaCha20Poly1305Test`, `SoundshareAudioRtpSenderTest`,
  `VideoRtpSenderTest`, `VideoRtpSenderVp8Test`,
  `DefaultScreenShareClientTest` — stay in `:shared:voice/jvmTest`. They
  reference both the cipher (still in voice) and the moved types (now
  resolved through `api(projects.shared.voiceCodec)`). No import changes
  required (FQNs unchanged).

## 7. Critic pass (Step 3 — self-review)

| Concern | Resolution |
|---|---|
| `internal` visibility on moved types — does cross-module `internal` work? | No — `internal` is module-scoped. The moved types are currently `internal` in `:shared:voice`; once moved to `:shared:voice-codec`, `:shared:voice` JVM call sites that used `internal class VoicePacketCodec`, `internal class NonceGenerator`, `internal interface AeadCipher`, `internal object RtpPacket` will fail to resolve. **Required visibility change**: promote `AeadCipher`, `NonceGenerator`, `RtpPacket`, `VoicePacketCodec` to `public` in their new home. This is a deliberate API decision — they are now part of the public surface of `:shared:voice-codec` (which is the entire point of the extraction). Documented in module KDoc. |
| `ExperimentalAtomicApi` opt-in — is this a "temporary" violating HARD RULE #2? | No. `kotlin.concurrent.atomics` is the canonical KMP atomic API; it carries the experimental annotation until Kotlin 2.2 stable promotion. Using it is the conceptually-correct KMP choice. The opt-in annotation is the standard Kotlin migration path, not a workaround. |
| `xchacha20Poly1305(...)` factory left in `:shared:voice` — does any commonMain caller need it? | No. The only commonMain caller in `:shared:voice` is `AeadCipher.kt` itself which declared the `expect fun`. After move, no commonMain code references it. JVM call sites (`DefaultVoiceClient`, `VideoRtpSender`, etc.) all stay in `:shared:voice/jvmMain` and continue to import `dev.puklic.voice.crypto.xchacha20Poly1305` — package unchanged, file unchanged, only the surrounding module structure changes. |
| Does `:shared:voice` commonMain still compile after `AeadCipher.kt` leaves? | The `expect fun xchacha20Poly1305(...)` declaration leaves with the rest of the file — but the actual `xchacha20Poly1305` factory needs to remain callable from `:shared:voice/jvmMain` after the move. Plan: keep a thin JVM-only file `:shared:voice/jvmMain/.../crypto/XChaCha20Poly1305Factory.kt` containing `internal fun xchacha20Poly1305(key: ByteArray): AeadCipher = BcXChaCha20Poly1305(key)` and merge it into the existing `XChaCha20Poly1305Jvm.kt` (drop the `actual` keyword, drop the cross-module `expect`/`actual` relationship). The interface `AeadCipher` is now imported from `:shared:voice-codec`. No commonMain code in `:shared:voice` references `xchacha20Poly1305` after the move. |
| iOS targets enabled but no iOS `actual`? | Correct — there is no `expect fun` in `:shared:voice-codec` anymore (it moved-and-dropped together with the cipher factory). The interface `AeadCipher` is platform-independent. iOS compilation succeeds because there is no platform-dependent declaration in commonMain. |
| `:shared:voice-codec` android target — required? | `puklic.kmp-library` enables it by default. Module remains buildable on android even if no android consumer exists yet — uniform source-set hierarchy, no extra cost. |
| `verifyIosNoGplDeps` impact? | `:ios:app` does not gain a dependency on `:shared:voice-codec` in this slice. The task continues to pass unchanged. (FP-4..6 will add the dep with iOS actuals, and the task will still pass because voice-codec is Apache-2.0 pure-Kotlin.) |
| HARD RULE #4 EA SoT — N/A, no endpoint URLs touched. | — |
| HARD RULE #5 — N/A, no remote env. | — |

No findings requiring redesign.

## 8. dep-policy.md update

Add row above `:shared:voice`:

```
| `:shared:voice-codec` | desktop + iOS shared graph (via :shared:voice today; direct in FP-4..6) | Apache-2.0 only | KMP-wide pure-Kotlin Discord voice transport codec (RTP framing, nonce sequencing, AEAD interface + glue). No native deps, no cipher impl. Extraction rationale: `architect-reports/2026-05-29-fp1-voice-codec-extraction.md`. |
```

## 9. Done criteria (Step 11)

- All files `git mv`'d (history preserved).
- `:shared:voice-codec:build` green.
- `:shared:voice-codec:compileKotlinIosArm64`, `compileKotlinIosX64`,
  `compileKotlinIosSimulatorArm64` green.
- `:shared:voice:build` green (existing jvmTest passes).
- `:ios:app:verifyIosNoGplDeps` unchanged green.
- `dep-policy.md` updated.
- Conventional commit `refactor(voice): extract XSalsa20Poly1305Cipher +
  VoicePacketCodec to :shared:voice-codec (FP-1, #41)`.
- Issue #41 closed.

## 10. Slice-level scope guard

Out of scope for FP-1 (do NOT do here):
- iOS `AeadCipher` actual via CryptoKit — FP-4/5/6.
- Move `XChaCha20Poly1305Jvm.kt` itself — stays in `:shared:voice`. The
  BouncyCastle cipher is JVM-only and remains so.
- H.264 / UDP / VoiceUdpTransport — FP-2 / FP-3.
- `:shared:screencast` — FP-7.

If any of these creep in, redesign and re-scope.
