# FP-14h-2 — voice-gateway extraction (impl-role review + BLOCK)

Impl-role review of the FP-14h-1 move map (issue #64, architect report
`2026-05-29-fp14h-1-voice-gateway-survey.md`). This document is the
Step 2 self-critic per HARD RULE #1, performed BEFORE any `git mv`. It
identifies five blocking inconsistencies in the FP-14h-1 plan that, if
ignored, would force temporary / half-built state in the repo —
forbidden by HARD RULE #2 ("NEVER TEMPORARY, ALWAYS CONCEPTUAL").

Conclusion: **FP-14h-2 cannot ship as-scoped.** The work blocks on
FP-14h-1 re-scoping. No `git mv` performed; no source changes. Issue
#64 left OPEN; no commit pushed.

---

## §1 Exact file inventory (per task prompt Step 1)

Confirmed match between FP-14h-1 §8 move map and on-disk state at
`HEAD` of `main`. All 22 source files + 1 delete exist where the map
says they exist.

### commonMain (13 files)

| Path | Status |
|---|---|
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceOp.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayPayload.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayTransport.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/gateway/VoiceGatewayConnection.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/IpDiscovery.kt` | exists (BUT bundles `UdpRtpTransport` interface + `expect fun newUdpRtpTransport()` — see §3) |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/Vp8Packetiser.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Depacketizer.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/VideoFrameFragmenter.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/H264Fragmenter.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioCapture.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/audio/AudioPlayback.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/pipeline/CapturePipeline.kt` | exists |
| `shared/voice/src/commonMain/kotlin/dev/puklic/voice/screenshare/source/ScreenSourceEnumerator.kt` | NOT in §8 move map; mentioned in §2.1 — confirmed STAYS |

### jvmMain (9 files)

| Path | Status |
|---|---|
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/gateway/KtorVoiceGatewayTransport.kt` | exists; uses Ktor + WS — KMP-clean |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/SoundshareAudioRtpSender.kt` | exists |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VideoRtpSender.kt` | uses `java.util.concurrent.atomic.AtomicInteger` |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/VoicePacketDispatcher.kt` | exists; uses `Dispatchers.IO` |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundCapture.kt` | jvmMain actual |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundPlayback.kt` | jvmMain actual |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/audio/JavaSoundDevices.kt` | jvmMain actual |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/PlaybackPipeline.kt` | uses `j.u.c.ConcurrentHashMap`, `ConcurrentLinkedQueue`, `System.nanoTime()`, `JitterBuffer` |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt` | uses `j.u.c.ConcurrentHashMap`, `H264Decoder` (which is FFmpeg-GPL — STAYS!) |
| `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/crypto/XChaCha20Poly1305Jvm.kt` | uses BouncyCastle |

### delete (1)

`shared/voice/src/jvmMain/kotlin/dev/puklic/voice/transport/UdpRtpTransport.jvm.kt`

---

## §2 BLOCKER 1 — `internal` visibility cross-module break

FP-14h-1 §10.1 asserts that gateway types stay `internal` after the
move. Per §11 acceptance: *"gateway types remain `internal` to
`:shared:voice-codec`; …; factory exposure to the dep graph is via
`public` factory functions in a new `codec/client/Factories.kt`"*.

But the following `:shared:voice` files import the to-be-moved types
directly (NOT via factories):

| Consumer | Imports |
|---|---|
| `shared/voice/src/jvmMain/.../DefaultVoiceClient.kt` | `dev.puklic.voice.gateway.*`, `dev.puklic.voice.transport.UdpRtpTransport`, `…transport.newUdpRtpTransport`, `…transport.VoicePacketDispatcher`, `…transport.VideoRtpSender`, `…transport.SoundshareAudioRtpSender`, `…pipeline.PlaybackPipeline`, `…pipeline.CapturePipeline`, `…pipeline.IncomingVideoPipeline`, `…crypto.xchacha20Poly1305`, `…audio.JavaSoundCapture` etc. |
| `shared/voice/src/jvmMain/.../screenshare/DefaultScreenShareClient.kt` | `dev.puklic.voice.transport.UdpRtpTransport`, `…transport.VideoRtpSender`, `…transport.SoundshareAudioRtpSender` |
| 13 files under `shared/voice/src/jvmTest/…` | direct imports of the same `internal` types — these are tests for the to-be-moved code that the FP-14b contract owns |

Kotlin `internal` visibility is module-scoped. `:shared:voice` and
`:shared:voice-codec` are DIFFERENT Gradle modules → different Kotlin
compilation units → different `internal` visibility scopes. After the
move:

- `DefaultVoiceClient` (stays in `:shared:voice`) loses access to
  `VoiceGatewayConnection`, `UdpRtpTransport`, `VoicePacketDispatcher`,
  `VideoRtpSender`, `SoundshareAudioRtpSender`, `PlaybackPipeline`,
  `CapturePipeline`, `IncomingVideoPipeline`, `xchacha20Poly1305()`.
- `DefaultScreenShareClient` (stays) loses access to
  `UdpRtpTransport`, `VideoRtpSender`, `SoundshareAudioRtpSender`.
- All `:shared:voice` `jvmTest` tests stop compiling.

**Resolution paths**:

1. **A — Promote internals to `public`** in `:shared:voice-codec`.
   Conceptually correct if these types are the cross-module API for
   the voice transport layer. FP-14h-1 §11 forbids this.
2. **B — Move `DefaultVoiceClient` + `DefaultScreenShareClient` +
   their tests** into `:shared:voice-codec` jvmMain. But they pull in
   FFmpeg-GPL (`H264Decoder`, `H264Encoder`) and libdave (DAVE) which
   FP-14h-1 explicitly says stay in `:shared:voice`. Would require
   further surgery: split DAVE + FFmpeg dependencies into separate
   helpers that `DefaultVoiceClient` composes.
3. **C — Re-export with `public typealias`** in `:shared:voice` for
   every internal type. Bloats the API surface and contradicts the
   "minimum complexity" rule.
4. **D — Move the tests in scope** to `:shared:voice-codec/jvmTest`.
   But the task prompt forbids this slice from touching test files
   ("DO NOT modify test files (FP-14b owns them)").

None of A, B, C, D is achievable inside the FP-14h-2 scope as
delivered. The plan needs a precondition that re-scopes visibility.

---

## §3 BLOCKER 2 — `IpDiscovery.kt` bundles to-be-moved + to-be-deleted

The single file `shared/voice/src/commonMain/.../transport/IpDiscovery.kt`
contains THREE concerns:

1. `object IpDiscovery` — keep (move per §8).
2. `interface UdpRtpTransport` + `expect fun newUdpRtpTransport()` —
   the architect plan deletes the JVM `actual` (`UdpRtpTransport.jvm.kt`)
   without saying what happens to the `expect`. Two reads possible:
   - **R1**: the `expect` is also deleted; all callers move to
     `VoiceUdpTransport` (FP-3). But every consumer (`DefaultVoiceClient`,
     `DefaultScreenShareClient`, `VoicePacketDispatcher`,
     `VideoRtpSender`, `SoundshareAudioRtpSender`, `PlaybackPipeline`,
     `JvmVoiceUdpTransportFactory`) still uses the `UdpRtpTransport`
     surface. R1 requires migrating 8 callers — not in scope.
   - **R2**: the `expect` survives, the JVM `actual` re-implemented at
     a new path. But `UdpRtpTransport.jvm.kt` literally IS the `actual`
     for `newUdpRtpTransport()`. Deleting it without a replacement = no
     `actual` → JVM compile fails. The `BridgedUdpRtpVoiceTransport` in
     `JvmVoiceUdpTransportFactory.kt` wraps `newUdpRtpTransport()` so
     it needs the `expect` to keep working.

The move map has no answer here. Whichever reading we pick, source-edit
work spreads beyond the listed file set.

**Resolution paths**:

1. Split `IpDiscovery.kt` into `IpDiscovery.kt` (moves) + `UdpRtpTransport.kt`
   (also moves, `expect` becomes a deprecated bridge that delegates to
   `VoiceUdpTransport`).
2. Migrate all 8 consumers to `VoiceUdpTransport` in FP-14h-2 — out of
   the move-map scope.
3. Keep `UdpRtpTransport` interface + `expect` in `:shared:voice` and
   only move `IpDiscovery` object — requires a source split inside the
   file, not a `git mv`.

None of these is the documented FP-14h-1 plan.

---

## §4 BLOCKER 3 — `PlaybackPipeline` depends on `JitterBuffer` (not in move map)

`PlaybackPipeline.kt` line 92 uses `JitterBuffer()`. `JitterBuffer.kt`
lives in `shared/voice/src/jvmMain/.../pipeline/JitterBuffer.kt` and is
**NOT in the FP-14h-1 §8 move map.** It is `internal class JitterBuffer`,
KMP-clean (pure Kotlin, no JVM deps).

After moving `PlaybackPipeline` to `:shared:voice-codec/commonMain`,
the reference to `JitterBuffer` (still in `:shared:voice`) fails:
voice-codec cannot depend on voice (would be a circular dep —
`:shared:voice` already declares `api(projects.shared.voiceCodec)`).

**Resolution**: `JitterBuffer.kt` must also move. The §8 map is short
by 1 file. (Trivial fix but the plan is wrong as-published.)

Additionally `PlaybackPipeline` uses:

- `System.nanoTime()` — needs replacement with `kotlinx.datetime.Clock`
  or `kotlin.time.TimeSource.Monotonic`.
- `j.u.c.ConcurrentHashMap`, `j.u.c.ConcurrentLinkedQueue`,
  `@Volatile lastPacketNs`. Architect §8 row says "REWRITE concurrency"
  but does not specify the replacement primitives. `kotlinx.atomicfu`
  is mentioned in §12 risks but not added to deps anywhere in the
  plan's build-script delta (§11 says voice-codec commonMain adds
  `kotlinx.atomicfu` — needs `kotlinx-atomicfu` Gradle plugin too, not
  just a dep).
- `Dispatchers.IO` — exists on Native but is an alias of `Default`;
  per FP-14h-1 §10.2 this is accepted.

---

## §5 BLOCKER 4 — `IncomingVideoPipeline` depends on FFmpeg-GPL `H264Decoder`

`IncomingVideoPipeline.kt` line 4 imports `dev.puklic.voice.codec.H264Decoder`.
This is **not** the `:shared:voice-codec` `commonMain` `H264Decoder`
(which is an `expect` interface at
`shared/voice-codec/.../codec/video/H264Decoder.kt`). It is the
**`:shared:voice/jvmMain/.../codec/H264Decoder.kt`** — the FFmpeg-GPL
libavcodec H264 decoder that FP-14h-1 §2.2 explicitly marks as STAYS
in `:shared:voice` jvmMain due to GPL contamination.

Moving `IncomingVideoPipeline` to `:shared:voice-codec/commonMain`
while it imports the GPL `H264Decoder` violates the entire reason for
the split. The GPL decoder MUST NOT be on the iOS classpath.

**Resolution paths**:

1. Rewrite `IncomingVideoPipeline` to use the
   `:shared:voice-codec/commonMain` `H264Decoder` interface +
   `H264DecoderFactory` (FP-2). The current code constructs `H264Decoder()`
   inline (line 74), which is the JVM-GPL constructor. It must take an
   injected `H264DecoderFactory` instead.
2. Leave `IncomingVideoPipeline` in `:shared:voice` jvmMain and only
   ship the interface-based common version in FP-14h-4 alongside the
   Apple impls.

The architect plan §8 row says move-only. Plan inconsistent with §2.2
GPL-isolation rule.

---

## §6 BLOCKER 5 — `XChaCha20Poly1305` move = three artefacts, not one rename

§8 last row: `XChaCha20Poly1305Jvm.kt` → `XChaCha20Poly1305.jvm.kt` in
voice-codec jvmMain, "wrap in `actual fun xchacha20Poly1305(...)`."

But the corresponding `expect fun xchacha20Poly1305(key: ByteArray):
AeadCipher` does NOT exist yet anywhere. §9 lists the new
`XChaCha20Poly1305.kt` (expect) + `XChaCha20Poly1305.ios.kt` (CryptoKit
actual + HChaCha20 subkey derivation) as FP-14h-2 deliverables. So this
single "move" expands to:

1. Delete `internal fun xchacha20Poly1305(key)` from the source file.
2. Create new `:shared:voice-codec/commonMain/.../crypto/XChaCha20Poly1305.kt`
   with `internal expect fun xchacha20Poly1305(key): AeadCipher`.
3. Convert the moved JVM file into `internal actual fun
   xchacha20Poly1305(key) = BcXChaCha20Poly1305(key)` (the existing
   private class stays JVM, the public entry becomes the `actual`).
4. Create new `:shared:voice-codec/iosMain/.../crypto/XChaCha20Poly1305.ios.kt`
   with a real CryptoKit `ChaChaPoly` + HChaCha20 subkey impl
   (~60 LoC). FP-14h-1 §10.1 risk-table notes "CryptoKit `ChaChaPoly`
   ... supports it via `ChaChaPoly.seal(_:using:nonce:authenticating:)`"
   — but this is an iOS SDK Swift API not exposed to Kotlin/Native
   cinterop. The actual cinterop binding has to be authored, and there
   is no existing cinterop for CryptoKit anywhere in this repo. Real
   work, not a one-line wrap.

This is FP-14h-3-sized work, not a move.

---

## §7 BLOCKER 6 — `KtorVoiceGatewayTransport.kt` references `internal` types in another module post-move

The to-be-moved file `KtorVoiceGatewayTransport.kt` declares
`internal class KtorVoiceGatewayTransport` and `internal companion`,
plus a `public fun ktorVoiceGatewayTransportFactory(...)` that returns
`VoiceWsTransportFactory` whose constructor is `internal`. After moving
to `:shared:voice-codec/commonMain`:

- `:desktop:app` calls `ktorVoiceGatewayTransportFactory(httpClient)`
  to build `VoiceWsTransportFactory`, which it passes into
  `DefaultVoiceClient`. The `public` function is fine.
- `DefaultVoiceClient` (stays in `:shared:voice`) unpacks
  `VoiceWsTransportFactory.delegate` to get the `internal
  VoiceGatewayTransportFactory`. That `internal` access becomes a
  cross-module read after the move → compile fail.

Same family as BLOCKER 1, but called out separately because it affects
the public `:desktop:app` wiring path.

---

## §8 Library-first audit (per global memory)

No external library can satisfy the move-with-rewrite work
(`kotlinx.atomicfu` is already a transitive in some modules but not in
`:shared:voice-codec`; adding it is a build-script delta, not a new
library). The CryptoKit `ChaChaPoly` binding requires hand-authored
cinterop. The conclusion of FP-14h-1 §10.4 (no third-party KMP voice
gateway library exists) carries through.

---

## §9 Decision — BLOCK

Per HARD RULE #2 ("NEVER TEMPORARY, ALWAYS CONCEPTUAL"), I cannot
execute the §8 move map as documented because:

- The `internal` visibility plan (BLOCKER 1, BLOCKER 6) leaves
  `DefaultVoiceClient`, `DefaultScreenShareClient`, and 13 `jvmTest`
  files broken with no resolution path inside the impl scope.
- `IpDiscovery.kt`, `JitterBuffer.kt`, `IncomingVideoPipeline.kt`
  (BLOCKERS 2, 3, 4) require splits / refactors that the §8 map labels
  as plain `git mv`.
- `XChaCha20Poly1305` (BLOCKER 5) is misclassified as a move; it is in
  truth a new expect/actual surface with a substantial new iOS
  CryptoKit cinterop deliverable.
- The plan's build-script deltas (§11) omit the `kotlinx-atomicfu`
  Gradle plugin, the cinterop definition file for CryptoKit, and the
  Ktor `client-darwin` engine selection for iOS — all required for the
  KMP build to even reach link.

Per HARD RULE #2 "When a 'temporary' feels tempting … Block — file an
issue documenting the prerequisite + stop." This is exactly the
situation.

### Required follow-up before FP-14h-2 can ship

1. **Re-scope FP-14h-1** to decide visibility policy (path A in §2).
   The conceptually clean choice is to promote the gateway types to
   `public` (the voice-gateway IS a cross-module surface — both
   `DefaultVoiceClient` and `AppleNativeVoiceClient` will consume it).
   Annotate as `@PuklicVoiceInternal opt-in` to keep the namespace
   marked as "stable for in-tree consumers only", which is the
   idiomatic Kotlin way.
2. **Add JitterBuffer to the move map** (§8 amendment).
3. **Split FP-14h-2 into FP-14h-2a, FP-14h-2b, FP-14h-2c**:
   - FP-14h-2a: pure `git mv` of files that compile as-is — the 9
     commonMain Apache-2.0 KMP-clean ones (Vp8Packetiser,
     H264Depacketizer, VideoFrameFragmenter, H264Fragmenter, VoiceOp,
     VoiceGatewayPayload, VoiceGatewayTransport, AudioCapture,
     AudioPlayback) + visibility promotion if path-A approved.
   - FP-14h-2b: rewrite + move the concurrency-touching files
     (PlaybackPipeline, IncomingVideoPipeline, VideoRtpSender,
     VoicePacketDispatcher, SoundshareAudioRtpSender, CapturePipeline)
     with `kotlinx.atomicfu` + the H264Decoder dependency injection
     refactor.
   - FP-14h-2c: XChaCha20Poly1305 expect/actual + iOS CryptoKit
     cinterop (this is actually FP-14h-3-class work and belongs there).
   - FP-14h-2d: KtorVoiceGatewayTransport move with Darwin engine
     wiring.
   - `UdpRtpTransport.jvm.kt` delete only after `JvmVoiceUdpTransportFactory`
     stops bridging to it — a non-trivial caller migration.
4. **Add cinterop def for CryptoKit** (or use Apple `Security.framework`
   `CCCryptorCreateWithMode` BC-equivalent) — architectural decision.

### State of the repo on exit

- No `git mv` performed.
- No source file modified.
- `:shared:voice/build.gradle.kts` unchanged.
- `:shared:voice-codec/build.gradle.kts` unchanged.
- Issue #64 stays OPEN with this report linked as the blocking critic
  output.

---

## §10 Recommendation

Re-open FP-14h-1 (issue #63) for the visibility + split + cinterop
amendments listed in §9. Once those land, FP-14h-2 can be split into
the four sub-slices above and dispatched test-first per HARD RULE #1
step 5.

The conceptually-correct end state is preserved; only the slicing of
work changes. No temporary state has been introduced into the repo.
