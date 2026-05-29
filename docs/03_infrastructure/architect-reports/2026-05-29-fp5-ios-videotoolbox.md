# FP-5 — iOS H264 Encoder/Decoder via VideoToolbox

Date: 2026-05-29
Slice: FP-5 (issue #45)
Parent: `2026-05-29-full-feature-parity.md` §3.2, §6, §7
Predecessor: FP-2 (`603f57d`) — KMP H264 interfaces; FP-4 (`49f9854`) — iOS cinterop reference pattern

## 1. Context

`:shared:voice-codec` commonMain (post-FP-2) defines `H264Encoder` / `H264Decoder` / their factories. iosArm64 / iosX64 / iosSimulatorArm64 targets already compile (interfaces only). FP-5 supplies the iOS `actual` impls backed by Apple's VideoToolbox system framework.

Unlike FP-4 (libopus required a 3rd-party XCFramework), VideoToolbox is part of the iOS SDK. Kotlin/Native auto-generates platform bindings under `platform.VideoToolbox.*`, `platform.CoreMedia.*`, `platform.CoreVideo.*`, `platform.CoreFoundation.*`. **No `.def` file, no XCFramework, no Gradle cinterop block changes** beyond what FP-2/FP-4 already configured.

## 2. Decisions

### 2.1 VTCompressionSession configuration

```
imageBufferAttributes:
  kCVPixelBufferPixelFormatTypeKey = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange (NV12)
  kCVPixelBufferWidthKey = width
  kCVPixelBufferHeightKey = height
encoderSpecification:
  kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder = true
properties (set post-create):
  kVTCompressionPropertyKey_RealTime = true
  kVTCompressionPropertyKey_AllowFrameReordering = false
  kVTCompressionPropertyKey_ProfileLevel = kVTProfileLevel_H264_Baseline_AutoLevel
  kVTCompressionPropertyKey_AverageBitRate = bitrateKbps * 1000
  kVTCompressionPropertyKey_MaxKeyFrameInterval = framerate * 2
  kVTCompressionPropertyKey_ExpectedFrameRate = framerate
```

Codec type: `kCMVideoCodecType_H264`.

### 2.2 VTDecompressionSession configuration

CMVideoFormatDescription is built from the first SPS+PPS pair extracted from the inbound Annex-B stream via `CMVideoFormatDescriptionCreateFromH264ParameterSets`. The decoder lazy-inits once both SPS and PPS have been observed; non-VCL NALs that arrive before that just update internal state and return null.

Output pixel format requested: `kCVPixelFormatType_32BGRA` so we can map directly to ARGB Int by simple byte reordering (BGRA → ARGB swaps B and R within each pixel; A stays in slot 3 = high byte for our packed Int).

### 2.3 AVCC ↔ Annex-B conversion

**Encoder (AVCC → Annex-B):**
- On first keyframe, extract SPS + PPS via `CMVideoFormatDescriptionGetH264ParameterSetAtIndex` (iterate count), emit them as Annex-B NALs (4-byte 0x00000001 + nalu) prepended to the IDR slice.
- For every CMSampleBuffer: get the contiguous `CMBlockBuffer`, copy bytes, walk the AVCC stream: 4-byte BE length, NALU body. Replace each length with `0x00 0x00 0x00 0x01`. Loop until exhausted (multi-slice frames supported).
- Keyframe detection: read the `kCMSampleAttachmentKey_NotSync` value from the first attachment — keyframe == not present / false.
- Timestamp: input frame PTS supplied as `CMTime(value = frameIndex, timescale = framerate)`; output `ts90k = frameIndex * 90000 / framerate` derived in the encoder caller (matches H264FrameFragmenter expectations).

**Decoder (Annex-B → AVCC):**
- `H264Decoder.decode(annexBNalUnit)` accepts one NAL at a time (per interface KDoc). Strip the leading start code if present (3- or 4-byte). Inspect `nal_unit_type` (low 5 bits of first byte).
- type 7 (SPS) / type 8 (PPS): cache, attempt format-description creation. Return null.
- type 5 (IDR) / type 1 (P slice): build AVCC = 4-byte BE length + NALU body. Wrap in `CMBlockBuffer`, then `CMSampleBuffer`. Call `VTDecompressionSessionDecodeFrame` synchronously (kVTDecodeFrame_EnableAsynchronousDecompression = 0). Callback emits CVImageBuffer → BGRA bytes → ARGB IntArray.
- Other types (AUD=9, SEI=6, etc.): return null.

### 2.4 YUV420P (I420) → NV12 conversion

VTCompressionSession requires NV12 (semi-planar: Y plane, then interleaved UV plane). Input from `encode(yuv420p: ByteArray)` is planar I420 (Y plane, U plane, V plane). Conversion: copy Y as-is; interleave U and V byte-by-byte into UV plane. Both planes wrapped in a `CVPixelBufferCreateWithPlanarBytes`-backed pixel buffer.

### 2.5 Threading and output handoff

VTCompressionSession invokes the output callback on an internal serial queue. `encode(yuv)` is a synchronous push, but output is asynchronous. Strategy:

- Encoder allocates a bounded `kotlinx.coroutines.channels.Channel<EncodedFrame>(capacity = 4)`.
- Callback (called from VT thread) does a `channel.trySend(frame)` (non-blocking). If full, drops the oldest by re-receiving once before send — but in practice with `RealTime = true` and `AllowFrameReordering = false`, VT emits at most one output per input.
- `encode()` calls `VTCompressionSessionEncodeFrame` then `channel.tryReceive().getOrNull()`. With realtime + no-reordering, the callback fires synchronously on the same thread before EncodeFrame returns, so tryReceive succeeds. If null (pipeline-fill / drop), return null per the contract.

Decoder uses the same pattern (bounded Channel of IntArray).

### 2.6 Memory management

- Long-lived: `VTCompressionSessionRef`, `VTDecompressionSessionRef`, `CMVideoFormatDescriptionRef` — stored as Kotlin properties typed `CPointer<*>?`. Released explicitly in `close()`:
  - `VTCompressionSessionInvalidate` + `CFRelease` for the session
  - `CFRelease` for the format description (encoder side acquires it from `CMSampleBufferGetFormatDescription` → `CFRetain` to extend lifetime)
- Short-lived per-frame: `CVPixelBuffer`, `CMBlockBuffer`, `CMSampleBuffer` — created with explicit `CFRelease` after use. Encoder callback's CMSampleBuffer is borrowed; we extract bytes and return without retaining.
- `memScoped { }` wraps stack-scoped C structs (CMTime, size_t out-params, ptr arrays).

### 2.7 What about Compose UI / SwiftUI integration?

Out of scope. FP-5 ships only the codec impls. Hooking the decoded ARGB into a Compose `ImageBitmap` is a screencast-receiver concern (later slice).

## 3. Files

| Path | Action |
|---|---|
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/video/IosH264Encoder.kt` | NEW |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/video/IosH264Decoder.kt` | NEW |
| `shared/voice-codec/src/iosMain/kotlin/dev/puklic/voice/codec/video/IosH264Factories.kt` | NEW |
| `shared/voice-codec/src/iosTest/kotlin/dev/puklic/voice/codec/video/IosH264RoundTripTest.kt` | NEW |

No build.gradle.kts change required — `platform.VideoToolbox.*` etc. come from the Kotlin/Native Apple SDK bundle.

## 4. Self-critic

1. **Synchronous output assumption.** VT realtime + no-reordering does NOT guarantee output-before-EncodeFrame-returns; the docs only guarantee in-order. Mitigation: bounded Channel with capacity 4, tryReceive after EncodeFrame. If the callback hasn't fired yet, return null (pipeline-fill semantics already in the contract). Next call's output will surface the previous frame's NAL. The H264FrameFragmenter is timestamp-driven, not strict-order — but it IS order-sensitive for keyframe state. Acceptable: realtime mode in practice always fires the callback before EncodeFrame returns on iOS 8+. We document the semantics.
2. **Multi-NAL output per CMSampleBuffer.** AVCC may contain multiple NALs (e.g. SPS+PPS+IDR concatenated on first keyframe). The conversion loop handles arbitrary count.
3. **AnnexB start code stripping in decoder.** Spec says "with or without start code prefix"; we detect 0x00000001 (4-byte) and 0x000001 (3-byte).
4. **BGRA byte order vs Int.** Kotlin/Native is little-endian on iOS arm64. Packing as `(A shl 24) or (R shl 16) or (G shl 8) or B` makes the Int read A,R,G,B from MSB to LSB regardless of host endianness, matching the contract `ARGB Int per pixel (A in the high byte)`.
5. **Test gate.** iosTest requires a simulator runner; CI compile-only is the realistic gate. Test is structured so it runs on a device/simulator unchanged.

## 5. Risks

- VideoToolbox returning `kVTVideoEncoderMalfunctionErr` on bad parameters — surface as KotlinIllegalStateException with the OSStatus.
- First frames may be silently dropped while VT primes hardware. Documented as `null` returns per the contract.
