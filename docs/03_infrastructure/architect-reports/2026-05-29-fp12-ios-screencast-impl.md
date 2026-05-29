# FP-12 — iOS ScreenCapture actual (ReplayKit + VideoToolbox) + locked confirm dialog UX

**Date**: 2026-05-29
**Slice of**: `2026-05-29-full-feature-parity.md` §3.3 + §7
**Issue**: JanDamek/puklic#52
**Predecessors**: FP-5 (`IosH264Encoder`), FP-7 (`:shared:screencast` contracts), FP-11 (`BroadcastExtensionBridge`).
**UX status**: HARD RULE #3 user approval obtained 2026-05-29 (Option A + audio ON default). Locked design implemented verbatim.

## 1. Goal

1. iOS `actual` of `ScreenCapture` / `ScreenSourceEnumerator` / `ScreenCaptureFactory` in `:shared:screencast` iosMain that plugs `BroadcastExtensionBridge` (FP-11) BGRA frames into `IosH264Encoder` (FP-5) and exposes the audio Float32 stream as `Flow<ShortArray>`.
2. iOS `actual` of `VoiceDock` in `:shared:compose-ui` iosMain that mirrors the JVM structure but, on share-screen tap, opens the user-approved Material3 confirm dialog whose primary action is a styled `RPSystemBroadcastPickerView` UIKitView host.

## 2. Reuse + small refactors

`VoiceStatusBar.kt`, `VoiceSettingsDialog.kt`, `ScreenSharePickerDialog.kt` currently live in `:shared:compose-ui/jvmMain` although they import only Compose Multiplatform symbols + `:shared:voice-api` (which is KMP). Moving them to `commonMain` is the conceptually-correct fix that makes the iOS `VoiceDock` actual reachable without duplicating component code — this is a pure relocation, no behaviour change, JVM consumers' imports keep resolving because the FQN is unchanged.

`IncomingVideoPane.kt` depends on `java.awt.image.BufferedImage` for the RGBA → ImageBitmap conversion. It becomes `expect fun IncomingVideoPane(...)` in `commonMain` with the existing JVM body as the jvm `actual` and a new iOS `actual` using `org.jetbrains.skia.Image.makeRaster(...)`. No stubbing, no "v1 limitation" — both actuals do real work.

`androidMain` already has its own `VoiceDock.android.kt` no-op; not affected.

## 3. iOS ScreenCapture pipeline

```
BroadcastExtensionBridge.videoFrames()           Flow<BgraFrame>
    ↓ pipelineScope coroutine
BgraToYuv420 (hand-rolled NV12 conversion)       ByteArray (I420)
    ↓ IosH264Encoder.encode(yuv420p)
EncodedFrame Channel (capacity 4, DROP_OLDEST)   Flow<EncodedFrame>  ← exposed as ScreenCapture.frames

BroadcastExtensionBridge.audioFrames()           Flow<PcmAudioChunk>   (Float32 stereo 48 kHz)
    ↓ Float32 → S16 conversion
Flow<ShortArray>  ← exposed as ScreenCapture.audio
```

### 3.1 BGRA → YUV420 (I420) — hand-rolled

Apple `Accelerate.framework` vImage offers `vImageConvert_ARGB8888To420Yp8_CbCr8` but the iOS Kotlin/Native Apple SDK does not expose `Accelerate` as a default `platform.*` package, and adding a `.def` for it is out of scope for this slice. Hand-rolled Kotlin/Native conversion is fast enough at 1280×720 — the inner loop is 2 MAC per pixel for Y plane (~3.7 M ops) + 8 MAC per 2×2 block for UV planes (~3.7 M ops), well under 5 ms per frame on A12+ at the cap the BroadcastExtensionBridge enforces. Critical:

- Use ITU-R BT.601 limited range coefficients (matches Discord's expected colour primaries — the JVM `LibavVideoEncoder` libswscale path also defaults to BT.601 with `SWS_FAST_BILINEAR`).
- 2×2 chroma subsample averaging — read 4 source pixels per UV sample.
- Allocate the I420 buffer once per capture session (sized to the frame the extension sent — width × height × 3 / 2) and reuse across frames via a `var`.

### 3.2 Float32 PCM → S16 PCM

`PcmAudioChunk.floatPayload` is interleaved Float32 stereo 48 kHz. Conversion to S16: clamp to [-1.0, 1.0] then multiply by `Short.MAX_VALUE`. Same algorithm the JVM screencast already uses. Per-chunk allocation of a `ShortArray(payloadBytes / 4)`.

### 3.3 Coroutine + dispatcher

`IosScreenCapture` owns a `SupervisorJob`-rooted `CoroutineScope(Dispatchers.Default)`. The pipeline coroutine:

1. Collects `bridge.videoFrames()` on the same scope.
2. Lazily instantiates `IosH264Encoder` on the first frame (need width/height from the actual capture — the synthetic `ScreenSource.Monitor(width=0, height=0)` does not know).
3. Pushes encoded frames through a `Channel(capacity = 4, onBufferOverflow = DROP_OLDEST)` exposed as the `frames` Flow.
4. Closes encoder + channel on capture `close()`.

`close()` is idempotent: cancels the scope, closes the encoder, closes the audio channel.

### 3.4 Encoder configuration

Bitrate 1500 kbps, framerate 30, even dimensions enforced by trimming to nearest lower even number (BGRA frames from ReplayKit are device-resolution and may be odd e.g. 1170×2532 — trim to 1170×2532, both already even on current iPhones, but the guard is mandatory for future iPad portrait variations).

## 4. Synthetic `ScreenSource`

iOS has no per-monitor enumeration (full device screen only). `IosScreenSourceEnumerator.list()` returns `listOf(ScreenSource.Monitor(id = "ios-screen", displayName = "This iPhone", widthPx = 0, heightPx = 0))`. The picker UX (`IosShareScreenConfirmDialog`) only has one button anyway — the user does not pick a source, only confirms.

## 5. Compose UI — locked UX

### 5.1 `IosShareScreenConfirmDialog`

Material3 `AlertDialog` with:

- title `"Share your iPhone screen?"`
- text body `"The broadcast will capture everything on your screen, including other apps."`
- `Switch` row `"Share app audio"` — `var shareAudio by remember { mutableStateOf(true) }` (default ON, per locked design)
- `confirmButton` = `BroadcastPickerHost(shareAudio = shareAudio, onUserTapped = onPrimaryAction)` — a `UIKitView` hosting `RPSystemBroadcastPickerView` styled to LOOK like a Material3 `FilledButton`
- `dismissButton` = `TextButton(onClick = onDismiss)` reading `"Cancel"`

### 5.2 `BroadcastPickerHost` (Compose iOS UIKitView interop)

Implementation:

- `androidx.compose.ui.viewinterop.UIKitView { RPSystemBroadcastPickerView(frame: 0,0,240,40) }`
- Configure inside `factory`: `preferredExtension = "cz.damek.puklic.app.broadcast"`, `showsMicrophoneButton = false`
- Traverse subviews to find the internal `UIButton`; restyle it:
  - `backgroundColor = UIColor.systemBlue` (matches Material3 FilledButton primary)
  - `tintColor = UIColor.white`
  - `layer.cornerRadius = 20.0` (Material3 button corner radius)
  - Remove default `imageView` tint so the white icon stays visible.
- `onUserTapped` callback fires via a `UITapGestureRecognizer` attached to the picker view (mirrors the FP-11 SwiftUI wrapper). Used to capture the current `shareAudio` value into a Kotlin-side state object before the system sheet appears — the `IosScreenCaptureFactory` reads it back when `start()` is invoked next by the host.

Apple discourages subview-traversal styling but does not forbid it — common practice in third-party iOS apps for matching brand styling. If iOS changes the internal hierarchy in a future release, the styling silently falls back to the system default (which is still a working button). The picker functionality is unaffected — only cosmetics.

### 5.3 `VoiceDock.ios.kt`

Mirrors `VoiceDock.jvm.kt`:

- Observes `voiceClient.state`, `screenShare.state`, `incomingVideo`, `daveState`, `participants`.
- DAVE downgrade banner (existing common component) on transitions.
- `IncomingVideoPane(incomingVideo)` — calls the new `expect/actual` pane.
- `VoiceStatusBar(...)` — calls the moved-to-common component.
- On share-screen pick: opens `IosShareScreenConfirmDialog` (replaces `ScreenSharePickerDialog` on this platform).
- Settings dialog / verify-call dialog identical to JVM.

The single synthetic `ScreenSource` is the only source — `listSources()` is called inside the confirm flow and the result becomes the argument to `voiceClient.screenShare.start(source, shareAudio = shareAudio)`.

## 6. Files

| Path | Action |
|---|---|
| `shared/compose-ui/src/jvmMain/kotlin/dev/puklic/ui/components/voice/VoiceStatusBar.kt` | `git mv` → commonMain (no body change) |
| `shared/compose-ui/src/jvmMain/kotlin/dev/puklic/ui/components/voice/VoiceSettingsDialog.kt` | `git mv` → commonMain (no body change) |
| `shared/compose-ui/src/jvmMain/kotlin/dev/puklic/ui/components/voice/ScreenSharePickerDialog.kt` | `git mv` → commonMain (no body change) |
| `shared/compose-ui/src/jvmMain/kotlin/dev/puklic/ui/components/voice/IncomingVideoPane.kt` | Split: `expect` declaration in commonMain, JVM body stays in jvmMain `actual` |
| `shared/compose-ui/src/iosMain/kotlin/dev/puklic/ui/components/voice/IncomingVideoPane.ios.kt` | NEW — Skia-based iOS `actual` |
| `shared/compose-ui/src/iosMain/kotlin/dev/puklic/ui/components/voice/IosShareScreenConfirmDialog.kt` | NEW — Material3 confirm dialog |
| `shared/compose-ui/src/iosMain/kotlin/dev/puklic/ui/components/voice/BroadcastPickerHost.kt` | NEW — UIKitView host for `RPSystemBroadcastPickerView` |
| `shared/compose-ui/src/iosMain/kotlin/dev/puklic/ui/screens/main/VoiceDock.ios.kt` | EDIT (replaces existing stub) |
| `shared/screencast/src/iosMain/kotlin/dev/puklic/screencast/ios/IosScreenCapture.kt` | NEW |
| `shared/screencast/src/iosMain/kotlin/dev/puklic/screencast/ios/IosScreenSourceEnumerator.kt` | NEW |
| `shared/screencast/src/iosMain/kotlin/dev/puklic/screencast/ios/IosScreenCaptureFactory.kt` | NEW |
| `shared/screencast/src/iosMain/kotlin/dev/puklic/screencast/ios/BgraToYuv420.kt` | NEW — hand-rolled BT.601 NV12 converter |
| `shared/screencast/src/iosTest/kotlin/dev/puklic/screencast/ios/IosScreenCaptureContractTest.kt` | NEW — compile-only contract test |
| `shared/screencast/build.gradle.kts` | EDIT — `:shared:voice-codec` (already api transitively) verified for iOS encoder access |
| `shared/compose-ui/build.gradle.kts` | EDIT — iosMain depends on `:shared:screencast` (for synthetic source enum), no Android impact |

## 7. Self-critic

| Concern | Resolution |
|---|---|
| BGRA→YUV at 30 fps on A12 | Hand-rolled BT.601 limited range; per-frame allocations limited to one ByteArray pre-sized; inner loop O(w·h) with integer math. Benchmarks left for FP-13/integration slice. |
| Subview styling of `RPSystemBroadcastPickerView` | Apple discourages but does not forbid. If hierarchy changes in a future iOS release, styling silently falls back to default — picker remains functional. Cosmetic-only risk. |
| `shareAudio` value race between toggle change and broadcast start | Toggle value is read via `remember` state in Compose; passed to `IosScreenCaptureFactory.create(source, shareAudio = ...)`. The Swift system sheet appears *after* `start()` was called with the locked value — no race. |
| `IncomingVideoPane` Skia path correctness | `Image.makeRaster(ImageInfo(width, height, RGBA_8888, OPAQUE), bytes, rowBytes = width * 4).toComposeImageBitmap()` is the canonical Compose-iOS path. Same call shape Compose Multiplatform itself uses internally for `BitmapPainter`. |
| `IosScreenCapture` on iOS sim (x64/arm64) where no ReplayKit extension runs | `BroadcastExtensionBridge` is the bridge — on simulator with no extension, `videoFrames()` suspends forever on the lifecycle signal. Acceptable: simulator builds are CI-only; functional testing requires a device with the entitlements provisioned. No "stub" path. |
| `frameIndex` overflow | Long-typed; at 30 fps wraps in 9.7×10^9 years. Ignored. |
| TODO / temporary | None. Every path does real work. No `_unused`, no `// for now`. |

## 8. Verification (Step 6)

```
./gradlew :shared:screencast:build
./gradlew :shared:screencast:compileKotlinIosArm64
./gradlew :shared:screencast:compileKotlinIosX64
./gradlew :shared:screencast:compileKotlinIosSimulatorArm64
./gradlew :shared:compose-ui:compileKotlinIosArm64
./gradlew :shared:compose-ui:compileKotlinIosX64
./gradlew :shared:compose-ui:compileKotlinIosSimulatorArm64
./gradlew :ios:app:linkReleaseFrameworkIosArm64
./gradlew :ios:app:linkReleaseFrameworkIosX64
./gradlew :ios:app:linkReleaseFrameworkIosSimulatorArm64
./gradlew :ios:app:verifyIosNoGplDeps
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
    -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO
```

All 12 must pass.
