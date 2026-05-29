# FP-14c — Mac App Store Apple-native codec wrappers (impl plan)

Status: IMPL ROLE plan + critic (HARD RULE #1 Steps 2 + 3 + 6). Test contract is FP-14b
(`f1651a0`). Architectural framing locked in FP-14a (`2026-05-29-fp14a-mac-app-store-architect.md`).
Date: 2026-05-29
Author: impl role — refs Issue #56

> HARD RULE #2 ("NEVER TEMPORARY") and minimum-complexity in full force.
> No TODO, no stubs, no `for now`, no fallback shims.

---

## 1. Goal

Introduce a new JVM-only Gradle module `:desktop:platform-macos-appstore` containing
Apache-2.0 JNA bridges over Apple-native codec / network primitives so the Mac App Store
variant of `:desktop:app` (FP-14d scope) can satisfy the same `:shared:voice-codec`
common-main interfaces (`H264Encoder`, `H264Decoder`, `OpusEncoder`, `OpusDecoder`,
`VoiceUdpTransport`) without pulling FFmpeg-GPL JavaCPP onto its classpath.

This slice satisfies the RED contract tests from FP-14b in
`desktop/app/src/macAppStoreTest/kotlin/...`:

- `JnaVideoToolboxH264EncoderContractTest` — loadable class + implements `H264Encoder` +
  factory implements `H264EncoderFactory`.
- `JnaLibopusEncoderContractTest` / `JnaLibopusDecoderContractTest` — loadable classes +
  implement `OpusEncoder` / `OpusDecoder`.
- `JnaNwConnectionUdpTransportContractTest` — loadable transport + factory.

The entitlements / fastlane / workflow tests stay RED — FP-14d/e scope.

---

## 2. Library survey (delta vs FP-14a §2)

FP-14a §2.1/§2.2 already concluded:

- VideoToolbox: no Maven-Central JVM binding exists; bridge directly via JNA. Apache-2.0.
- libopus: reuse our own libopus, BSD-3-Clause. FP-14a §2.2 selected reuse of the
  `Opus.xcframework/macos-arm64` slice. **Refinement here**: the xcframework slice is a
  static `.a`; JNA needs a `.dylib`. We therefore re-link the static slice into a
  universal `libopus.dylib` via `clang -dynamiclib -force_load`. Same upstream binary
  bits — one product-wide libopus build. The dylib lives at
  `desktop/platform-macos-appstore/libs/libopus.dylib` (committed; ~600 KB).
- Network.framework: no Maven-Central JVM binding; bridge directly via JNA. Apple system
  framework, no shipping needed.

The existing xcframework build script (`dist/apple/build-libopus-xcframework.sh`) only
emits iOS slices. We extend it to add a `macos-arm64_x86_64` slice (macOS device arm64 +
macOS x86_64 lipo'd) so future maintenance has one SSOT build script. The macOS slice is
then re-linked into the JNA-loadable dylib by a sibling script
`dist/apple/build-libopus-dylib-from-xcframework.sh`.

JNA is already on the classpath (5.16.0 in `:shared:screencast` jvmMain). kotlinx-coroutines
already on the classpath. Kermit already on the classpath. No new Maven deps.

---

## 3. Module shape

```
desktop/platform-macos-appstore/
├── build.gradle.kts                  # applies puklic.jvm-library; deps: voice-codec, voice-api, jna, coroutines, kermit
├── libs/
│   └── libopus.dylib                 # committed; produced by build-libopus-dylib-from-xcframework.sh
└── src/main/kotlin/dev/puklic/desktop/macappstore/
    ├── bridge/
    │   ├── CoreFoundation.kt         # JNA Library: CFRelease, CFRetain
    │   ├── VideoToolbox.kt           # JNA Library: VT*, CMSampleBuffer, CVPixelBuffer
    │   ├── CoreMedia.kt              # JNA Library: CMTime, CMBlockBuffer, CMVideoFormatDescription
    │   ├── CoreVideo.kt              # JNA Library: CVPixelBuffer*
    │   ├── Network.kt                # JNA Library: nw_*
    │   ├── Dispatch.kt               # JNA Library: dispatch_* + dispatch_data_*
    │   └── Libopus.kt                # JNA Library bound to libopus.dylib
    ├── codec/
    │   ├── JnaVideoToolboxH264Encoder.kt
    │   ├── JnaVideoToolboxH264Decoder.kt
    │   ├── JnaLibopusEncoder.kt
    │   └── JnaLibopusDecoder.kt
    └── transport/
        ├── JnaNwConnectionUdpTransport.kt
        └── JnaNwConnectionUdpTransportFactory.kt
```

`desktop/app/build.gradle.kts` is amended to add
`implementation(projects.desktop.platformMacosAppstore)` to the `macAppStoreTest` source
set's `compileClasspath` + `runtimeClasspath`. No other module is altered.

`settings.gradle.kts` adds `include(":desktop:platform-macos-appstore")`.

---

## 4. Memory + threading

### 4.1 VideoToolbox

- `VTCompressionSessionCreate` / `VTDecompressionSessionCreate` return retained CF objects;
  `CFRelease` on `close()`.
- Output callback is a `JNA Callback`. VideoToolbox invokes it on its internal serial
  queue. Inside the callback we `CFRetain` the `CMSampleBufferRef` parameter (it is autoreleased
  by VT), pull the AVCC bytes + format-description NAL parameter sets, push the resulting
  `EncodedFrame` onto an `ArrayDeque` guarded by the encoder instance's monitor, then
  `CFRelease`. Mirrors `IosH264Encoder` exactly except the static C function is a JNA
  Callback rather than a Kotlin/Native `staticCFunction`.
- `VTCompressionSessionEncodeFrame` is documented as synchronous under
  `RealTime=true + AllowFrameReordering=false`. We do not block on a Channel — we mirror
  the iOS impl which simply checks the queue after the synchronous call returns.
- AVCC → Annex-B conversion: identical algorithm to FP-5 `convertAvccToAnnexB` —
  reads 4-byte big-endian length prefix, slices out the NAL, prepends `00 00 00 01`.

### 4.2 libopus

- `opus_encoder_create` / `opus_decoder_create` return malloc'd pointers; `opus_encoder_destroy` /
  `opus_decoder_destroy` on `close()`.
- One libopus state per encoder/decoder instance. Encoders / decoders are not thread-safe
  per upstream docs; one instance per stream is the contract enforced by `OpusEncoder` /
  `OpusDecoder` Kotlin interfaces.
- Static initializer in the JNA `Libopus` Library interface loads `libopus.dylib`.
  Load strategy: `Native.load("opus", LibopusLibrary::class.java)` — JNA's standard
  search includes the directory the .jar/.class file lives in plus `jna.library.path`.
  At runtime inside the .app bundle FP-14d sets `jna.library.path` to
  `Contents/Resources` where jpackage places the dylib (see FP-14d). For test runs the
  Gradle task adds `desktop/platform-macos-appstore/libs/` to `jna.library.path`.

### 4.3 Network.framework

- `nw_connection_create` returns a refcounted handle. JNA holds the `Pointer`; we balance
  by `nw_release` via the `nw_*` helpers; in practice once `nw_connection_cancel` runs
  Network.framework releases internal state. The pattern mirrors `IosVoiceUdpTransport`.
- One serial `dispatch_queue_t` per connection. JNA Callbacks bridge:
  - `nw_connection_set_state_changed_handler` — block taking
    `(nw_connection_state_t, nw_error_t)`. Used to resolve a `CompletableDeferred<Unit>`
    readiness future.
  - `nw_connection_send` — completion block taking `(nw_error_t)`. Wrapped in
    `suspendCancellableCoroutine`.
  - `nw_connection_receive_message` — completion block taking
    `(dispatch_data_t, nw_content_context, bool, nw_error_t)`. Re-armed after each
    received datagram (Apple's API is strictly one-shot).

Apple blocks (the `^{}` literals) — JNA cannot construct them directly; we reuse the
exact `BlockLiteral` + `_NSConcreteGlobalBlock` trick from FP-8's
`ScreenCaptureKitBridge.completionBlock()`. A small shared helper `AppleBlock.kt` lives
in the `bridge/` package.

---

## 5. AVCC → Annex-B + parameter set extraction

Identical algorithm to FP-5 `IosH264Encoder.kt`. Each emitted NAL becomes
`0x00 0x00 0x00 0x01 ‖ payload`. On the first keyframe we read the H.264 SPS+PPS via
`CMVideoFormatDescriptionGetH264ParameterSetAtIndex` and inline them ahead of the IDR.

For the decoder we strip the Annex-B start code, prepend a 4-byte big-endian length,
pack into a `CMBlockBuffer`, build a `CMSampleBuffer`, push into VT. Output goes through
a `VTDecompressionOutputCallback` returning a `CVImageBuffer` — we lock, copy BGRA bytes,
convert to ARGB ints, unlock, `CFRelease`.

---

## 6. libopus.dylib build

`dist/apple/build-libopus-dylib-from-xcframework.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
XCF="$REPO_ROOT/shared/voice-codec/libs/Opus.xcframework"
SLICE="$XCF/macos-arm64_x86_64/libopus.a"
OUT="$REPO_ROOT/desktop/platform-macos-appstore/libs/libopus.dylib"

[ -f "$SLICE" ] || { echo "missing $SLICE — run dist/apple/build-libopus-xcframework.sh first" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
clang -dynamiclib -arch arm64 -arch x86_64 \
  -install_name @rpath/libopus.dylib \
  -Wl,-force_load,"$SLICE" \
  -o "$OUT"
codesign --sign - --force "$OUT"
echo "$(file -b "$OUT") — $(stat -f%z "$OUT") bytes"
```

The xcframework script is extended to also emit the macOS slice. Concretely:

- New `build_arch macosx arm64 arm-apple-darwin -mmacosx-version-min=13.0`
- New `build_arch macosx x86_64 x86_64-apple-darwin -mmacosx-version-min=13.0`
- New lipo `macos-fat/libopus.a`
- Added to `xcodebuild -create-xcframework` as a 3rd `-library` slice.

The committed `Opus.xcframework.sha256` manifest is regenerated.

---

## 7. Self-critic (HARD RULE #1 Step 3)

### 7.1 HARD RULE #2 — no temporary

- The dylib is the FINAL artifact for the Mac App Store ship. No "phase 2 library swap".
- The xcframework gets a real macOS slice — not a "if-it-exists" shim.
- Module deps are final: voice-api + voice-codec common only, no GPL artifact paths.

### 7.2 Minimum-complexity

- One module, ~7 small JNA bridge files (each one Library interface), 6 implementation
  classes. Reuses FP-8's existing `AppleBlock` pattern for Objective-C blocks.
- No new build plugin. `puklic.jvm-library` is reused.
- `jna.library.path` strategy works in both Gradle test invocations and the packaged
  Mac App Store app.

### 7.3 Library-first

- libopus reuse via xcframework — same binary as iOS.
- JNA reuse — already on classpath.
- VideoToolbox / Network.framework — system frameworks, no shipping.
- No new Maven Central deps.

### 7.4 Threading + GPL boundary

- VideoToolbox output callbacks fire on VT's internal serial queue — the encoder's
  shared `ArrayDeque<EncodedFrame>` is accessed under a `synchronized(this)` block.
  Single-producer single-consumer in steady state but the lock is conceptually correct.
- Network.framework callbacks fire on the per-connection serial dispatch queue — the
  `incomingChannel` is a `Channel.BUFFERED` so `trySend` is concurrency-safe.
- `:desktop:platform-macos-appstore` depends only on `:shared:voice-codec` and
  `:shared:voice-api` (both KMP commonMain). It does NOT depend on `:shared:voice`. The
  GPL boundary holds mechanically.

### 7.5 No critical findings.

---

## 8. References

- Issue #56
- FP-14a (`2026-05-29-fp14a-mac-app-store-architect.md`) — module structure, entitlements,
  library survey
- FP-14b (`2026-05-29-fp14b-test-first.md`) — RED contract tests
- FP-5 (`shared/voice-codec/src/iosMain/.../IosH264Encoder.kt`) — VideoToolbox encode reference
- FP-4 (`shared/voice-codec/src/iosMain/.../IosOpusCodec.kt`) — libopus reference
- FP-6 (`shared/voice-codec/src/iosMain/.../IosVoiceUdpTransport.kt`) — Network.framework reference
- FP-8 (`shared/screencast/src/jvmMain/.../macos/ScreenCaptureKitBridge.kt`) — JNA bridging pattern
