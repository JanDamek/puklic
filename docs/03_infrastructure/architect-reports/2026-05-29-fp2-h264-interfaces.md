# FP-2 — H264Encoder / H264Decoder KMP interfaces in :shared:voice-codec

**Date**: 2026-05-29
**Slice**: FP-2 of full-feature-parity refactor
**Issue**: #42
**Predecessor**: FP-1 (`8978e6e`)
**Author**: Claude (architect)
**Status**: Step 2 design — pre-approved blanket per 2026-05-28 macro (non-UX surface)

## 1. Goal

Introduce a KMP-clean codec surface in `:shared:voice-codec` commonMain so the upcoming iOS / macOS App Store builds (FP-5 VideoToolbox) and any future JVM push-frame encoder share one contract:

```kotlin
// commonMain
public data class EncodedFrame(...)   // moved from :shared:voice
public interface H264Encoder : AutoCloseable
public interface H264Decoder : AutoCloseable
public interface H264EncoderFactory
public interface H264DecoderFactory
```

This is the foundation; no platform impl ships in FP-2. Rationale below.

## 2. Decision — `interface` over `expect class`

Per #42 §Approach and architect report `2026-05-29-full-feature-parity.md` §7, we lock option (a):

- Plain `interface` in commonMain
- Per-platform implementations live in platform source sets and are wired via factory interfaces

Reasoning:

- `expect class` without a matching `actual` on every declared target breaks compilation of that target. FP-2 ships no JVM `actual` and no iOS `actual`; an `expect class` would force us to add empty / throwing stubs — forbidden by HARD RULE #2 (NEVER TEMPORARY).
- `interface` lets us add a JVM impl in a later slice (when a JVM push-frame caller actually exists) without changing the public surface.
- Constructor parameters (`width`, `height`, `bitrateKbps`, `framerate`) move into the factory's `create(...)` method. This is also more KMP-idiomatic — DI-friendly, no reflection-style instantiation through `expect class` constructors.

## 3. `EncodedFrame` reuse — keep existing signature

`EncodedFrame` already exists at `shared/voice/src/commonMain/.../transport/AnnexBSplitter.kt`:

```kotlin
data class EncodedFrame(val bytes: ByteArray, val ts90k: Int, val keyframe: Boolean)
```

Used by: `AnnexBSplitter`, `VideoFrameFragmenter`, `Vp8Packetiser`, `VideoEncoder`, `FfmpegVideoEncoder`, `LibavVideoEncoder`, `VideoRtpSender`, and the corresponding tests.

Decision: **move the existing type to `:shared:voice-codec` commonMain, keep the field names and types unchanged.** The architect report §3.2 sketch (`data`, `isKeyframe`, `ts90k: Long`) is NOT adopted — that sketch was illustrative; renaming + retyping would churn every transport caller for zero benefit. `ts90k: Int` is the correct width (RTP timestamps are 32-bit; an `Int` matches the RFC 3550 header field).

Mechanics:

1. `git mv` the `EncodedFrame` declaration out of `AnnexBSplitter.kt` into a new file `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/video/EncodedFrame.kt`.
2. Keep the package `dev.puklic.voice.transport` for now so existing imports in `:shared:voice` (which already depends on `:shared:voice-codec` post-FP-1) keep resolving. Renaming the package would force edits across ~10 files for no behaviour change — out of scope for FP-2.
3. `AnnexBSplitter.kt` keeps only the `AnnexBSplitter` object.

## 4. JVM impl bridge — **not shipped in FP-2**

The task brief asked for `JvmH264EncoderFactory` that constructs `LibavVideoEncoder`. The shapes do not align:

- `H264Encoder.encode(yuv420p: ByteArray): EncodedFrame?` — push-frame, caller supplies pixels.
- `LibavVideoEncoder.encode(): Flow<EncodedFrame>` — pull-frame, the encoder owns the input (libavdevice → decoder → swscale → libx264).

Writing a `JvmH264EncoderFactory` adapter today would have to throw on `encode(yuv420p)` (libavdevice does not accept caller-pushed frames), which is the forbidden stub per HARD RULE #2. The conceptually correct JVM impl is a new `LibX264PushEncoder` that wraps libavcodec/libx264 directly — but **there is no JVM caller** that pushes YUV bytes today. Shipping it speculatively contradicts the "no code without a current caller" line of HARD RULE #2.

This mirrors the brief's own concession on the decoder ("if `LibavVideoDecoder` exists; else leave unimplemented — that is FINE because no current desktop caller decodes incoming video"). Same principle applied to the encoder.

What ships in FP-2:

- Interfaces + `EncodedFrame` in `:shared:voice-codec` commonMain.
- A compile-only contract test in `commonTest`.
- No JVM `H264EncoderFactory` / `H264DecoderFactory` implementation.
- No iOS implementation — FP-5 owns that via VideoToolbox cinterop.

What remains unchanged:

- `:shared:voice`'s `LibavVideoEncoder` + `VideoEncoder` interface — the screen-capture-pipeline shape that desktop actually uses today. FP-7 (`:shared:screencast`) will move that pipeline into its own module without bending it into the codec shape.

## 5. Files touched

| Change | Path |
|---|---|
| Move (split) | `shared/voice/src/commonMain/kotlin/dev/puklic/voice/transport/AnnexBSplitter.kt` → keep splitter only |
| New | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/transport/EncodedFrame.kt` |
| New | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/video/H264Encoder.kt` |
| New | `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/codec/video/H264Decoder.kt` |
| New | `shared/voice-codec/src/commonTest/kotlin/dev/puklic/voice/codec/video/H264InterfaceContractTest.kt` |
| Update | `shared/voice-codec/build.gradle.kts` header comment |

`:shared:voice/build.gradle.kts`, `LibavVideoEncoder`, `DefaultScreenShareClient`, transport code — untouched.

## 6. Self-critic (Step 3)

- **EncodedFrame stays in `dev.puklic.voice.transport`, not `dev.puklic.voice.codec.video`.** Slight package incoherence, but the alternative (rename + edit ~10 callers) violates minimum-complexity. Accepted.
- **No JVM impl in FP-2.** Risk: someone misreads the slice as "JVM codec landed". Mitigated by this report being the canonical reference and by the commit message stating "KMP interfaces + no platform impl yet".
- **No iOS impl in FP-2.** Explicit — FP-5 owns it. iOS targets compile because plain interfaces are fully resolved by Kotlin metadata only.
- **`H264Decoder.decode` return type `IntArray?` (ARGB pixels).** Inherited from the architect report sketch. An ARGB `Int` array is platform-neutral and zero-copy-friendly for both Skia (Compose) and CGImage (iOS). Keep.
- **No `EncodedFrame` field rename.** Diverges from the report sketch, justified above.

## 7. Acceptance gates (Step 6)

- `./gradlew :shared:voice-codec:build` green
- `./gradlew :shared:voice-codec:compileKotlinIosArm64` green
- `./gradlew :shared:voice-codec:compileKotlinIosX64` green
- `./gradlew :shared:voice-codec:compileKotlinIosSimulatorArm64` green
- `./gradlew :shared:voice:build` green
- `./gradlew :ios:app:verifyIosNoGplDeps` green (interfaces are pure Kotlin, no GPL pull-in)
