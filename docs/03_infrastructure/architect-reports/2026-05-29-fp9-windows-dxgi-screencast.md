# FP-9 — Windows DXGI Output Duplication + WASAPI loopback screencast actual

**Date**: 2026-05-29
**Issue**: JanDamek/puklic#49
**Slice of**: full-feature-parity refactor — see
`docs/03_infrastructure/architect-reports/2026-05-29-full-feature-parity.md`
§3.3 + §7. Predecessor slice: FP-7 (`:shared:screencast` module created,
Linux PipeWire actual moved in). Parallel slice: FP-8 (macOS
ScreenCaptureKit actual). FP-10 wires Windows into `:desktop:app`.

Pre-approved per the blanket non-UX architectural authorisation for the
feature-parity refactor (refactor only, no user-visible change to existing
desktop UX; new Windows distribution channel not yet wired up).

## 1. Goal

Add a Windows JVM `actual` implementation of the
`:shared:screencast` contracts (`ScreenCapture`,
`ScreenCaptureFactory`, `ScreenSourceEnumerator`) backed by:

- **Video**: `IDXGIOutputDuplication` (DXGI 1.2 — Windows 8+) for monitor
  capture at GPU framebuffer rate. Frames are returned as physical-pixel
  BGRA8 textures, mapped to CPU memory, converted to YUV420P, and fed
  into a push-frame `H264Encoder` (libx264 via the FFmpeg-GPL bundle
  already on the JVM classpath).
- **Audio**: WASAPI loopback capture (`AUDCLNT_STREAMFLAGS_LOOPBACK`)
  off the default render endpoint, downsampled to 48 kHz / stereo / S16
  to match the `Flow<ShortArray>` contract that `SoundshareAudioRtpSender`
  consumes (same shape as `PipeWireAudioReader` on Linux).

Encoder choice: **libx264** is retained on Windows. The desktop `.exe` /
`.msi` distribution channel ships under GPL (the Microsoft Store accepts
GPL apps, unlike the Mac App Store). VideoToolbox / Media Foundation
hardware encoders are deliberately out of scope for this slice — they'd
land as a follow-up perf optimisation, not a licensing prerequisite.

## 2. Reuse audit (Step 1)

In-repo (no new Gradle dep):

- **JNA 5.16.0** (`libs.jna`, `libs.jna-platform`) already on the JVM
  classpath via `:shared:platform-api` and `:shared:voice`. JNA's
  built-in Windows bindings (`com.sun.jna.platform.win32.*`) cover
  `Kernel32`, `User32` (incl. `EnumDisplayMonitors`), `Ole32`
  (`CoInitializeEx` / `CoUninitialize`), `WinNT` (HRESULT types,
  `GUID`). They do **not** cover DXGI / D3D11 / WASAPI directly —
  those COM interfaces require manual vtable-slot bindings.
- **FFmpeg-GPL bundle** (`libs.javacpp` + `libs.ffmpeg.bindings`) already
  pulled into `:shared:screencast/jvmMain` for the Linux libavdevice
  path. The same artefact ships:
    - `libavcodec` with `libx264` for push-frame H.264 encode
      (`avcodec_send_frame` / `avcodec_receive_packet`).
    - `libswscale` for BGRA → YUV420P conversion.
    - `libswresample` for Float32 stereo → S16 stereo audio
      conversion.
  Reuse via the existing `runtimeOnly("org.bytedeco:ffmpeg:<v>:<classifier>")`
  line — extended below in §3 to include `windows-x86_64-gpl`.
- **`AnnexBSplitter`** in `:shared:voice-codec` commonMain — same NAL
  splitter `LibavVideoEncoder` uses on Linux. Reused inside
  `JvmLibavH264Encoder` to satisfy the Annex-B-stripped-per-NAL
  contract of `EncodedFrame`.
- **`ScreenSource.Monitor`** in `:shared:voice-api` commonMain. The
  Windows enumerator emits one `Monitor` per `IDXGIOutput`; the `id`
  field carries `adapterIndex:outputIndex` so the capture impl can
  reattach without re-enumeration.

## 3. JNA + COM bridging approach

### 3.1 Pattern

JNA does not bind COM vtables. The chosen idiom (used widely in
`jna-platform`'s `com.sun.jna.platform.win32.COM` package) is:

1. Call the C entry point that returns the root COM interface
   (`CreateDXGIFactory1`, `D3D11CreateDevice`, `CoCreateInstance` for
   the MMDeviceEnumerator class). The returned pointer is the address
   of the object, which dereferences to the address of the vtable.
2. For each method call, read the function pointer out of the vtable
   at the documented slot index, build a JNA `Function`, and invoke it
   with the COM `this` pointer as the first argument plus the
   declared method arguments. The return value is the COM `HRESULT`.

The slot indices come from the MSDN-published vtable layout. Every
COM interface starts with the three `IUnknown` slots
(`QueryInterface`=0, `AddRef`=1, `Release`=2); derived interfaces
append their own methods after the inherited slots in the order
declared in the IDL.

This is the same idiom JNA's own `com.sun.jna.platform.win32.COM.Unknown`
class uses internally. We do not subclass `Unknown` because:

- `Unknown` only exposes the three `IUnknown` slots — we'd need to
  reach into the vtable for everything else anyway.
- Wrapping every COM interface as a JNA `Structure` subclass produces
  ~3× the LOC for zero runtime benefit.

The bridge layer therefore uses thin `value class`-style wrappers
holding a `Pointer` and providing typed Kotlin methods that internally
do vtable-slot dispatch.

### 3.2 Native libraries loaded

- `Dxgi.dll` — `CreateDXGIFactory1`.
- `D3d11.dll` — `D3D11CreateDevice`.
- `User32.dll` — `EnumDisplayMonitors`, `GetMonitorInfoW`. Already in
  jna-platform as `User32.INSTANCE`; reused.
- `Ole32.dll` — `CoInitializeEx`, `CoUninitialize`, `CoCreateInstance`,
  `CoTaskMemFree`. Already in jna-platform as `Ole32.INSTANCE`;
  reused.
- `Kernel32.dll` — `WaitForSingleObject` for the WASAPI event-driven
  capture loop, `CreateEventW`, `CloseHandle`. Already in jna-platform.

### 3.3 GUIDs

The DXGI / D3D11 / WASAPI / Audio COM identifiers are declared as
`GUID` constants inside the bridge files. Values are copied from the
MSDN header definitions (`dxgi.h`, `d3d11.h`, `mmdeviceapi.h`,
`audioclient.h`), with each declaration commented with the C macro
name for traceability.

## 4. DXGI capture flow

### 4.1 Initialisation

1. `CoInitializeEx(COINIT_APARTMENTTHREADED)` on the capture thread.
2. `CreateDXGIFactory1(IID_IDXGIFactory1, ppFactory)`.
3. `IDXGIFactory1::EnumAdapters1(adapterIndex, ppAdapter)` — iterate
   until `DXGI_ERROR_NOT_FOUND`.
4. For each adapter, `IDXGIAdapter1::EnumOutputs(outputIndex, ppOutput)`
   — iterate until `DXGI_ERROR_NOT_FOUND`.
5. `IDXGIOutput::QueryInterface(IID_IDXGIOutput6, ppOut6)` — Windows
   10 1803+. Fallback to `IDXGIOutput1` (Windows 8+) when QI returns
   `E_NOINTERFACE`. The downgrade matters for HDR-only fields; for
   SDR BGRA8 the duplication API is identical, so we transparently
   accept the older type.
6. `D3D11CreateDevice(adapter, HARDWARE, NULL, 0, [11_0], 1,
   D3D11_SDK_VERSION, &device, &featureLevel, &context)` — single
   feature level, no debug flags, no software fallback.
7. `IDXGIOutput6::DuplicateOutput(device, &dupl)`. Failure modes
   handled:
   - `E_ACCESSDENIED` — secure desktop active (UAC prompt); retry once
     after a short backoff, then surface as an `IllegalStateException`.
   - `DXGI_ERROR_UNSUPPORTED` — older OS / no Output6 — fall back to
     `IDXGIOutput1::DuplicateOutput`.

### 4.2 Frame loop

Per-frame:

1. `AcquireNextFrame(timeoutMs=16, &frameInfo, &resource)`.
   - `DXGI_ERROR_WAIT_TIMEOUT` — no new frame this tick; sleep 1 ms
     and continue (the encoder doesn't need a frame if nothing
     changed; the libx264 GOP timer will still produce a keyframe at
     `GOP_SIZE` regardless).
   - `DXGI_ERROR_ACCESS_LOST` — desktop mode switch / resolution
     change / Ctrl+Alt+Del. Release the duplication object and
     re-run §4.1 step 7. After three consecutive failures, surface as
     a fatal error to the consumer.
2. `IDXGIResource::QueryInterface(IID_ID3D11Texture2D, &tex)`.
3. Lazily create a CPU-readable staging texture sized to the duplication
   `DXGI_OUTPUT_DESC1` width/height with `CPU_ACCESS_READ` and
   `USAGE_STAGING`.
4. `ID3D11DeviceContext::CopyResource(staging, tex)`.
5. `ID3D11DeviceContext::Map(staging, 0, D3D11_MAP_READ, 0, &mapped)`
   — yields a row-pitched BGRA8 pointer.
6. Read the pixels into a heap `ByteArray` (tight-packed, no row
   padding) row by row using the returned `RowPitch`.
7. `Unmap(staging, 0)`. `IDXGIResource::Release()`.
   `IDXGIOutputDuplication::ReleaseFrame()`.

The BGRA bytes are then handed to `BgraToYuv420Converter` (libswscale
wrapper, defined in §6), and the resulting YUV420P bytes are fed to
the `H264Encoder` push-frame interface.

### 4.3 DPI / scaling

`DXGI_OUTPUT_DESC1::DesktopCoordinates` reports physical-pixel
coordinates; the duplicated texture is in physical pixels regardless
of the per-monitor DPI scale. This is exactly the contract our consumer
(`SoundshareAudioRtpSender`-adjacent video sender) wants — no
adjustment required. `IDXGIOutput6::GetDesc1` results are read once
at initialisation and stored on the `WindowsScreenCapture` instance.

### 4.4 HDR / wide gamut

`DXGI_FORMAT_R16G16B16A16_FLOAT` (HDR) is reported via
`DXGI_OUTPUT_DESC1::ColorSpace`. For v1 we accept SDR BGRA8 only
(`DXGI_FORMAT_B8G8R8A8_UNORM` reported by `AcquireNextFrame`). HDR
sources are auto-tonemapped by the OS only when the duplication is
created on an Output6 with `B8G8R8A8_UNORM` requested — which is the
default and what we use. A future slice can replace this with an
FP16 path when Discord supports HDR; out of scope here per the
mission statement.

### 4.5 Multi-GPU

DXGI handles multi-GPU transparently: each `IDXGIOutput` is attached
to exactly one `IDXGIAdapter`, and `DuplicateOutput` only succeeds
when called with a `D3D11Device` created on that adapter. The
enumeration in §4.1 stores `(adapterPtr, outputPtr)` together; the
capture impl creates the D3D11 device on the adapter the picked
output lives on. No user choice required.

## 5. WASAPI loopback audio flow

### 5.1 Initialisation

1. `CoInitializeEx(COINIT_APARTMENTTHREADED)` — once per thread; the
   capture impl uses a dedicated thread (the FFmpeg encoder thread
   pulls Float32 buffers via a `Channel`).
2. `CoCreateInstance(CLSID_MMDeviceEnumerator, ..., IID_IMMDeviceEnumerator,
   &enum)`.
3. `IMMDeviceEnumerator::GetDefaultAudioEndpoint(eRender, eConsole,
   &device)`.
4. `IMMDevice::Activate(IID_IAudioClient, CLSCTX_ALL, NULL, &client)`.
5. `IAudioClient::GetMixFormat(&pwfx)` — returns the engine's native
   `WAVEFORMATEX*`. We force-convert to 48 kHz stereo Float32 by
   passing this `pwfx` to `Initialize` only if it already matches; if
   not, we copy + edit the format struct to set
   `wFormatTag=WAVE_FORMAT_EXTENSIBLE`,
   `nSamplesPerSec=48000`,
   `nChannels=2`,
   `wBitsPerSample=32`,
   `SubFormat=KSDATAFORMAT_SUBTYPE_IEEE_FLOAT`.
   WASAPI shared-mode loopback honours the format we request via the
   mix-format conversion the audio engine performs automatically.
6. `IAudioClient::Initialize(AUDCLNT_SHAREMODE_SHARED,
   AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
   hnsBufferDuration=20ms, 0, pwfx, NULL)`. Event-driven mode lets us
   block on a Win32 event instead of polling, matching the latency
   target of 20 ms.
7. `CreateEventW` → handle; `IAudioClient::SetEventHandle(hEvent)`.
8. `IAudioClient::GetService(IID_IAudioCaptureClient, &cap)`.
9. `IAudioClient::Start()`.

### 5.2 Read loop

Per-iteration:

1. `WaitForSingleObject(hEvent, 100)` — 100 ms safety timeout in case
   the audio engine stalls.
2. `IAudioCaptureClient::GetBuffer(&pData, &numFrames, &flags,
   &devicePos, &qpcPos)`. The returned data is interleaved Float32
   stereo at 48 kHz.
3. Convert Float32 → S16 per-sample with saturation clamp at
   `[-1.0f, 1.0f]` then multiply by `32767.0f` and round-truncate.
   `(AUDCLNT_BUFFERFLAGS_SILENT & flags) != 0` → emit a zero-filled
   ShortArray of the right size (the OS skips the buffer write for
   silence).
4. Append to a per-channel-pair shared ring buffer. Slice out 20 ms
   chunks (1920 shorts = 960 samples × 2 channels) and `send()` them
   into the `Flow<ShortArray>` channel.
5. `IAudioCaptureClient::ReleaseBuffer(numFrames)`.

The conversion uses libswresample (`swr_convert`) for safety even
though the format was forced — this absorbs corner cases where WASAPI
returns the engine's native format anyway (some drivers ignore the
shared-mode format request). Same pattern as `PipeWireAudioReader`
on Linux.

### 5.3 Teardown

`IAudioClient::Stop()`, `cap.Release()`, `client.Release()`,
`device.Release()`, `enum.Release()`, `CloseHandle(hEvent)`,
`CoUninitialize()`.

## 6. JVM push-frame H.264 encoder — `JvmLibavH264Encoder`

The architect note in `H264Encoder.kt` reserved this slot: *"A
push-frame libavcodec impl can land when a JVM caller actually needs
one (none exists today)."* FP-9 is that first JVM caller.
`JvmLibavH264Encoder` implements `H264Encoder` directly using the
encoder half of `LibavVideoEncoder` (no demuxer, no decoder — we
supply YUV420P frames directly):

- Constructor: `width`, `height`, `bitrateKbps`, `framerate`. Same
  surface as `H264EncoderFactory.create`.
- `avcodec_find_encoder_by_name("libx264")`, `avcodec_alloc_context3`,
  same options as `LibavVideoEncoder` (`preset=veryfast`,
  `tune=zerolatency`, `profile=baseline`, `x264-params=keyint=60:...`,
  GOP=60, B-frames=0). Identical parameters mean Discord clients see
  the same NAL-unit shape across Linux and Windows.
- `encode(yuv420p: ByteArray): EncodedFrame?` — wraps the buffer
  into an `AVFrame` (`av_frame_alloc` + `av_image_fill_arrays` on a
  reusable internal `AVFrame`), pushes via `avcodec_send_frame`,
  pulls via `avcodec_receive_packet`, splits Annex-B into NAL units
  using `AnnexBSplitter`, returns the **first** NAL unit per call.
  Remaining NALs are queued in an internal ArrayDeque and returned by
  subsequent `encode(...)` calls before re-entering libx264.
- The return-one-NAL-per-call contract matches the kdoc on
  `H264Encoder.encode`. WindowsScreenCapture's outer loop calls
  `encode` until it returns `null`, emitting each NAL on the
  `frames` Flow.

This class also lives in `:shared:screencast/jvmMain` because the
javacpp / FFmpeg-GPL deps are already there; adding it to
`:shared:voice-codec/jvmMain` would force voice-codec to pull
ffmpeg-platform-gpl, which is the GPL-isolation boundary FP-1/2
explicitly created. Conceptually the encoder is "screencast on JVM
desktop", not "any-platform codec".

Reused by Mac (FP-8) and Linux (existing push-frame path-less callers,
if any). Linux's `LibavVideoEncoder` keeps owning the source-driven
path; nothing forces it to migrate to push-frame.

## 7. BGRA → YUV420P converter

`BgraToYuv420Converter` (jvmMain):

- Wraps a `SwsContext` configured for `width × height,
  AV_PIX_FMT_BGRA → AV_PIX_FMT_YUV420P, SWS_FAST_BILINEAR`.
- Reusable across the capture lifetime (allocated once, freed in
  `close`).
- `convert(bgra: ByteArray): ByteArray` returns a tight-packed YUV420P
  buffer of size `width * height * 3 / 2`.
- `AutoCloseable`; the outer `WindowsScreenCapture` owns it.

Same libswscale that `LibavVideoEncoder` already uses; one extra
sws context per Windows session has negligible cost.

## 8. File layout

```
shared/screencast/src/jvmMain/kotlin/dev/puklic/screencast/windows/
    WindowsDxgiBridge.kt          — JNA bindings + COM vtable dispatch for
                                    DXGI/D3D11/User32 monitor enumeration
    WindowsWasapiBridge.kt        — JNA bindings + COM vtable dispatch for
                                    WASAPI loopback (MMDevice, AudioClient,
                                    AudioCaptureClient)
    WindowsScreenCapture.kt       — implements ScreenCapture; owns the DXGI
                                    capture loop, BGRA→YUV converter, and
                                    H264Encoder driving
    WindowsScreenSourceEnumerator.kt — implements ScreenSourceEnumerator
    WindowsScreenCaptureFactory.kt — object implementing ScreenCaptureFactory
    WindowsLoopbackAudioReader.kt — produces Flow<ShortArray> at 48 kHz
                                    stereo S16 (mirrors PipeWireAudioReader)
    JvmLibavH264Encoder.kt        — push-frame libx264 H264Encoder impl
    BgraToYuv420Converter.kt      — libswscale wrapper
```

All package `dev.puklic.screencast.windows` except
`JvmLibavH264Encoder` and `BgraToYuv420Converter` which live in
`dev.puklic.voice.screenshare.encoder` next to the existing
`LibavVideoEncoder` for symmetry — both are codec helpers, not
Windows-specific.

## 9. Build graph

`shared/screencast/build.gradle.kts`:

- Add `windows-x86_64-gpl` case to `detectFfmpegClassifier()` so a
  developer running Gradle on Windows picks the right native bundle.
  CI builds for Windows distribution will pull this artifact at
  package time.
- Add `implementation(libs.jna)` + `implementation(libs.jna.platform)`
  to `jvmMain.dependencies`. Both already on the desktop classpath
  transitively via `:shared:platform-api`; declaring them here makes
  the dependency explicit and unblocks any future module split.

No other module touched. `:shared:voice` keeps its existing JNA pull
(transitively via `:shared:screencast`). `:desktop:app` wiring is
FP-10's job.

## 10. Self-critic

| Concern | Resolution |
|---|---|
| `IDXGIOutputDuplication` requires per-session permission | It's per-user, not admin; matches a normal Discord client install. The portal-style consent UI Windows shows in 11 24H2+ is automatic when the duplication is requested. |
| Multi-GPU correctness | Enumerator stores `(adapterIndex, outputIndex)` together; capture creates the D3D11 device on the same adapter. DXGI rejects mismatched device-output pairs (`DXGI_ERROR_INVALID_CALL`); we fail fast in that case rather than silently producing zero frames. |
| DPI scaling vs encoder bitrate | Physical-pixel capture means a 4K monitor at 150% DPI still emits 4K frames. The default encoder bitrate (2.5 Mbps) matches Discord's "Source" tier; downscale-on-capture is a future tuning slice. |
| HDR sources blacken to SDR | Accepted limitation for v1 — explicitly called out in §4.4. Adding an FP16 path costs ~300 LOC and Discord doesn't accept HDR yet. |
| WASAPI loopback latency | Event-driven 20 ms buffer matches the soundshare frame cadence one-for-one; no extra re-buffering needed beyond the byte ring already in the read loop. |
| Float32 → S16 saturation | Per §5.2 the clamp is at `[-1.0, 1.0]` before the `* 32767`. WASAPI shared mode clips at `±1.0` at the engine boundary so values outside that range only appear from buggy apps; clamping protects the encoder against integer wrap. |
| Mac-host compile-only verification | All COM types are JNA `Pointer` wrappers — no native linkage at compile time. `:shared:screencast:build` succeeds on macOS; FP-10 runs the actual capture on a Windows GitHub Actions runner. |
| Possible TODO / stub regressions | None introduced. Every HRESULT non-zero path throws a typed exception with the HRESULT value in hex plus the COM method name. No `@Suppress("Unused...")` over-broad scopes. |

## 11. Tests

`shared/screencast/src/jvmTest/kotlin/dev/puklic/screencast/windows/WindowsScreenCaptureFactoryConstructionTest.kt`:

- Compile-only contract: instantiates `WindowsScreenCaptureFactory`
  (it's an `object`), references the static `create` signature
  through a function-reference (`WindowsScreenCaptureFactory::create`),
  and asserts the returned type's KClass is `WindowsScreenCapture`.
- The test does not call `create()` — that would call into
  `Dxgi.dll` which is absent on Mac CI. The compile-time wiring is
  what FP-9's "Mac host compile" acceptance gates on.

The Linux test suite already covers the `:shared:screencast` happy
path; FP-10 adds Windows runtime tests on the `windows-latest`
runner.

## 12. dep-policy.md update

No change. The Windows actuals share the GPL-3.0 classification
already applied to `:shared:screencast/jvmMain` for the Linux
libavdevice + libx264 deps. The JNA bindings are LGPL-2.1+ /
Apache-2.0 dual; both are compatible with our desktop GPL ship.

## 13. Done criteria

- [x] Architect plan written.
- [x] Self-critic resolved.
- [x] `WindowsDxgiBridge.kt` — JNA + COM vtable dispatch for the DXGI 1.2
  /
  D3D11 / Output6 surface listed in §3.
- [x] `WindowsWasapiBridge.kt` — JNA + COM vtable dispatch for the
  WASAPI loopback surface listed in §3.
- [x] `WindowsLoopbackAudioReader.kt` — 48 kHz stereo S16
  `Flow<ShortArray>` mirroring `PipeWireAudioReader`.
- [x] `WindowsScreenSourceEnumerator.kt` — one `Monitor` entry per
  `IDXGIOutput`.
- [x] `WindowsScreenCapture.kt` — implements `ScreenCapture`, owns
  DXGI loop + BGRA→YUV → `H264Encoder` push.
- [x] `WindowsScreenCaptureFactory.kt` — `object`
  implementing `ScreenCaptureFactory`.
- [x] `JvmLibavH264Encoder.kt` — push-frame libx264.
- [x] `BgraToYuv420Converter.kt` — libswscale wrapper.
- [x] `WindowsScreenCaptureFactoryConstructionTest.kt` — compile-only
  contract test.
- [x] `:shared:screencast:build`, `:shared:voice:build`,
  `:desktop:app:compileKotlin`,
  `:ios:app:verifyIosNoGplDeps` all green on Mac host.
- [x] No TODO, no stubs, no commented-out code.
