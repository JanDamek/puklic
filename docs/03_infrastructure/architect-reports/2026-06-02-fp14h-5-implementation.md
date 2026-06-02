# FP-14h-5 — H264Decoder SPI promotion + IncomingVideoPipeline move (impl report)

Status: shipped. Issue #62.

References:
- 2026-06-02-fp14h-applevoiceclient-promotion-plan.md (master plan, §2 row "FP-14h-5")
- 2026-05-29-fp2-h264-interfaces.md (FP-2 — initial H.264 SPI introduction)
- 2026-05-29-fp5-ios-videotoolbox.md (iOS VT actuals)
- 2026-05-29-fp14h-1-v2-voice-gateway-redesign.md (overarching voice-codec promotion plan)

## §1 What landed

### §1.1 Common `H264Decoder.decode()` contract widened

Old contract: `fun decode(annexBNalUnit: ByteArray): IntArray?` — packed ARGB ints, fixed
dimensions implied by the factory.

New contract: `fun decode(annexBNalUnit: ByteArray): DecodedFrame?` where
`DecodedFrame(rgba: ByteArray, width: Int, height: Int)` carries the actual decoded
dimensions from the bitstream. The factory's `width / height` parameters are demoted to
*hints* (Apple VT pre-allocates a destination `CVPixelBuffer`; libavcodec ignores them).

Rationale: `IncomingVideoPipeline` surfaces `IncomingVideoFrame(rgba, width, height)` to
the UI, so the underlying decoder must report per-frame dimensions. The packed-`Int` ARGB
layout was JVM-AWT friendly but doesn't generalise to a KMP commonMain consumer.

### §1.2 Platform implementations updated

| Platform | File | Output BGRA → RGBA conversion |
|---|---|---|
| iOS / iPadOS arm64 | `IosH264Decoder.kt` (`:shared:voice-codec/iosMain`) | `bgraPixelBufferToRgba` writes R, G, B, A bytes per pixel from the VT-decoded `CVPixelBuffer` |
| Mac App Store (JVM Compose Desktop) | `JnaVideoToolboxH264Decoder.kt` (`:desktop:platform-macos-appstore`) | `readBgraAsRgba` mirrors the iOS conversion via JNA |
| JVM desktop (GPL — libavcodec) | new `LibavH264Decoder.kt` (`:shared:voice/jvmMain`) | Reuses existing `sws_scale` to `AV_PIX_FMT_RGBA`, returns `DecodedFrame` directly |

### §1.3 `IncomingVideoPipeline` promoted to `:shared:voice-codec/commonMain`

- Deleted `shared/voice/src/jvmMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt`
- New `shared/voice-codec/src/commonMain/kotlin/dev/puklic/voice/pipeline/IncomingVideoPipeline.kt`
- Constructor injects `H264DecoderFactory` (FP-2 SPI) instead of constructing
  `H264Decoder` directly.
- JVM-only concurrency primitives swapped for KMP-portable equivalents (mirrors
  PlaybackPipeline FP-14h-4): `ConcurrentHashMap` → `MutableMap` guarded by
  `kotlinx.coroutines.sync.Mutex`. `stop()` is now suspend (acquires the mutex to close
  decoders and clear the maps under serial visibility).
- `Dispatchers.IO` → `Dispatchers.Default` (matches PlaybackPipeline + CapturePipeline
  precedent — KMP commonMain has no IO dispatcher on Kotlin/Native).

### §1.4 `DefaultVoiceClient` wires the JVM `LibavH264DecoderFactory`

`startPipelines()` now passes `decoderFactory = LibavH264DecoderFactory` to
`IncomingVideoPipeline`. iOS + Mac App Store DI graphs will pass their respective Apple-
native factories (`IosH264DecoderFactory`, `JnaVideoToolboxH264DecoderFactory`) when
DefaultVoiceClient is promoted to commonMain in FP-14h-6.

### §1.5 Tests

- `H264InterfaceContractTest` (commonTest) — added `decodedFrame_equalsAndHashCode`
  case + updated fake decoder.
- `IosH264RoundTripTest` (iosTest) — asserts `rgba.size == width * height * 4`.
- `IncomingVideoPipelineTest` (jvmTest, new) — fake `H264DecoderFactory` verifies:
  - factory.create is invoked lazily, exactly once per remote SSRC seen;
  - decoded frames surface via `frames`;
  - `stop()` closes every issued decoder and clears state.

## §2 What did NOT change

- `H264Encoder` SPI already existed (FP-2). No change needed for FP-14h-5 — the encoder
  path is consumed by `DefaultScreenShareClient` (GPL FFmpeg), which is gated behind
  the `ScreenShareClientFactory?` introduced in FP-14h-6.
- `OutgoingVideoPipeline` mentioned in the master plan does NOT exist in the repo today.
  Outgoing video is owned by `DefaultScreenShareClient` (`:shared:voice/jvmMain`), not a
  separate pipeline class. No move needed.
- `LibavVideoEncoder` push-frame JVM actual NOT introduced — no current JVM caller needs
  push-frame H264 encoding. Per HARD RULE #2 the codec SPI commonMain `H264EncoderFactory`
  ships without a JVM actual (only iOS + macOS App Store have one) — there is no caller
  that would resolve to it on JVM.

## §3 Verification

```
./gradlew :shared:voice-codec:jvmTest :shared:voice:jvmTest \
  :shared:voice-codec:compileKotlinIosArm64 \
  :desktop:app:verifyMacAppStoreNoGplDeps \
  :desktop:app:macAppStoreTest --no-configuration-cache
```

BUILD SUCCESSFUL (PlaybackPipelineTest flaky-stable; rerun green).
